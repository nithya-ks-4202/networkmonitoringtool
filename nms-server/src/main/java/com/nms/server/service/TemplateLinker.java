package com.nms.server.service;

import com.nms.server.domain.Host;
import com.nms.server.domain.HostFlags;
import com.nms.server.domain.Item;
import com.nms.server.domain.ItemFlags;
import com.nms.server.domain.ItemPreprocessing;
import com.nms.server.domain.TriggerDef;
import com.nms.server.domain.TriggerTag;
import com.nms.server.repository.HostRepository;
import com.nms.server.repository.ItemRepository;
import com.nms.server.repository.TriggerRepository;
import com.nms.server.trigger.ProblemService;
import com.nms.server.trigger.expression.ExpressionParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Copies a template's items and triggers onto a host.
 *
 * <p>Copied rather than referenced, because the copies then diverge: each one
 * gets its own collected values, its own error state and its own next-check
 * time. The link back to the template item is kept so a later template change
 * can be pushed down, and so the interface can show which objects are
 * template-managed and should not be edited directly.
 *
 * <p>Trigger expressions are rewritten as they are copied: an expression
 * written against {@code /template.camera/icmpping} has to become one against
 * {@code /camera-12/icmpping}, or every copy would evaluate the template's
 * non-existent data.
 */
@Service
public class TemplateLinker {

    private static final Logger log = LoggerFactory.getLogger(TemplateLinker.class);

    private final HostRepository hosts;
    private final ItemRepository items;
    private final TriggerRepository triggers;
    private final ProblemService problems;

    public TemplateLinker(HostRepository hosts,
                          ItemRepository items,
                          TriggerRepository triggers,
                          ProblemService problems) {
        this.hosts = hosts;
        this.items = items;
        this.triggers = triggers;
        this.problems = problems;
    }

    /** Links the named templates, adding to whatever is already linked. */
    @Transactional
    public void link(Host host, List<String> templateNames) {
        for (String templateName : templateNames) {
            Host template = findTemplate(host.getTenantId(), templateName);

            boolean alreadyLinked = host.getTemplates().stream()
                    .anyMatch(linked -> linked.getId().equals(template.getId()));
            if (!alreadyLinked) {
                host.getTemplates().add(template);
            }

            // Copied even when the template is already linked. Skipping here
            // meant a template could never gain anything after the fact: an
            // upgrade that added items and triggers to a template left every
            // host already using it untouched, with no way to catch up short
            // of unlinking -- which deletes the history too.
            //
            // Safe to repeat because the copy is idempotent: an item or
            // trigger the host already has is kept as it is rather than
            // replaced, so configuration made by hand survives.
            copyTemplate(template, host);
        }
        hosts.save(host);
    }

    /**
     * Replaces the set of linked templates.
     *
     * <p>Unlinking removes the objects the template created, along with their
     * history. That is destructive enough to be worth saying plainly in the
     * interface, but leaving orphaned items behind -- still collecting, no
     * longer explicable -- is worse.
     */
    @Transactional
    public void relink(Host host, List<String> templateNames) {
        Set<Long> wanted = new HashSet<>();
        for (String name : templateNames) {
            wanted.add(findTemplate(host.getTenantId(), name).getId());
        }

        List<Host> toUnlink = host.getTemplates().stream()
                .filter(template -> !wanted.contains(template.getId()))
                .toList();

        for (Host template : toUnlink) {
            unlink(host, template);
        }

        link(host, templateNames);
    }

    private void unlink(Host host, Host template) {
        Set<Long> templateItemIds = new HashSet<>();
        items.findByHostId(template.getId()).forEach(item -> templateItemIds.add(item.getId()));

        Set<Long> templateTriggerIds = new HashSet<>();
        triggers.findByHostId(template.getId())
                .forEach(trigger -> templateTriggerIds.add(trigger.getId()));

        List<Item> derived = items.findByHostId(host.getId()).stream()
                .filter(item -> item.getTemplateItem() != null
                        && templateItemIds.contains(item.getTemplateItem().getId()))
                .toList();

        // Triggers first: they reference the items, and removing the items
        // beneath a live trigger would leave it permanently unevaluatable.
        //
        // Matched against this template's triggers, not merely against having
        // come from some template. The looser test deleted every
        // template-derived trigger on the host, so removing one template from
        // a camera that also carried the storage template took the storage
        // triggers with it -- leaving the items still collecting, and nothing
        // left to alert on them.
        List<TriggerDef> derivedTriggers = triggers.findByHostId(host.getId()).stream()
                .filter(trigger -> trigger.getTemplateTrigger() != null
                        && templateTriggerIds.contains(trigger.getTemplateTrigger().getId()))
                .toList();
        // A problem outlives the trigger that raised it: nothing joins the two
        // tables, so deleting the trigger leaves its open problems open for
        // good. They sit on the Problems page and in the severity counts with
        // nothing left that could ever recover them, and any escalation
        // already running against them keeps firing. Closing them here is the
        // only moment the trigger is still available to write a recovery
        // event against.
        Instant now = Instant.now();
        for (TriggerDef trigger : derivedTriggers) {
            problems.resolveProblems(trigger, now);
        }

        triggers.deleteAll(derivedTriggers);
        items.deleteAll(derived);

        host.getTemplates().removeIf(linked -> linked.getId().equals(template.getId()));
        log.info("Unlinked template '{}' from host '{}': removed {} item(s) and {} trigger(s)",
                template.getName(), host.getTechnicalName(), derived.size(), derivedTriggers.size());
    }

    private void copyTemplate(Host template, Host host) {
        Map<Long, Item> copiedByTemplateItemId = new HashMap<>();

        for (Item templateItem : items.findByHostId(template.getId())) {
            if (templateItem.getFlags() == ItemFlags.PROTOTYPE) {
                // Prototypes are instantiated by discovery against the real
                // entities it finds, not copied as-is.
                continue;
            }
            items.findByHostIdAndKey(host.getId(), templateItem.getKey()).ifPresentOrElse(
                    existing -> {
                        // The host already has an item with this key, from
                        // another template or created by hand. Overwriting it
                        // would silently discard configuration someone made
                        // deliberately.
                        log.debug("Host '{}' already has item '{}'; leaving it as it is",
                                host.getTechnicalName(), templateItem.getKey());
                        copiedByTemplateItemId.put(templateItem.getId(), existing);
                    },
                    () -> {
                        Item copy = copyItem(templateItem, host);
                        items.save(copy);
                        copiedByTemplateItemId.put(templateItem.getId(), copy);
                    });
        }

        // Triggers are copied in two passes. The first creates them; the second
        // wires dependencies, which can only be done once every copy exists and
        // has an identity to point at.
        Map<Long, TriggerDef> copiedByTemplateTriggerId = new HashMap<>();

        // What the host already has, by description. Items are matched on key
        // a few lines above; triggers have no such identifier, and the
        // description is what an operator recognises them by. Without this a
        // second link would duplicate every trigger, so the same problem would
        // be raised twice and acknowledged once.
        Map<String, TriggerDef> existingTriggers = new HashMap<>();
        for (TriggerDef existing : triggers.findByHostId(host.getId())) {
            existingTriggers.put(existing.getDescription(), existing);
        }

        for (TriggerDef templateTrigger : triggers.findByHostId(template.getId())) {
            if (templateTrigger.getFlags() == com.nms.server.domain.TriggerFlags.PROTOTYPE) {
                continue;
            }

            TriggerDef existing = existingTriggers.get(templateTrigger.getDescription());
            if (existing != null) {
                // Kept as it is, including any threshold someone has tuned,
                // but still recorded so dependencies can point at it.
                copiedByTemplateTriggerId.put(templateTrigger.getId(), existing);
                continue;
            }

            TriggerDef copy = copyTrigger(templateTrigger, template, host);
            triggers.save(copy);
            copiedByTemplateTriggerId.put(templateTrigger.getId(), copy);
        }

        int dependencies = copyDependencies(template, copiedByTemplateTriggerId);

        log.info("Linked template '{}' to host '{}': {} item(s), {} trigger(s), {} dependency link(s)",
                template.getName(), host.getTechnicalName(),
                copiedByTemplateItemId.size(), copiedByTemplateTriggerId.size(), dependencies);
    }

    /**
     * Reproduces the template's trigger dependencies between the copies.
     *
     * <p>Without this a template's suppression structure is lost the moment it
     * is linked, and a single dead camera raises one problem per symptom --
     * offline, stream down, web interface unreachable, ONVIF not responding --
     * which is exactly the alert storm the dependencies exist to prevent.
     *
     * <p>A dependency on a trigger outside the template is kept pointing at the
     * original: it refers to a shared upstream device, and every host linking
     * the template should depend on that same one.
     */
    private int copyDependencies(Host template, Map<Long, TriggerDef> copies) {
        int linked = 0;

        for (TriggerDef templateTrigger : triggers.findByHostId(template.getId())) {
            TriggerDef copy = copies.get(templateTrigger.getId());
            if (copy == null) {
                continue;
            }

            for (TriggerDef dependency : templateTrigger.getDependencies()) {
                TriggerDef target = copies.getOrDefault(dependency.getId(), dependency);
                copy.getDependencies().add(target);
                linked++;
            }

            if (!copy.getDependencies().isEmpty()) {
                triggers.save(copy);
            }
        }
        return linked;
    }

    private Item copyItem(Item source, Host host) {
        Item copy = new Item();
        copy.setTenantId(host.getTenantId());
        copy.setHost(host);
        copy.setName(source.getName());
        copy.setKey(source.getKey());
        copy.setCheckType(source.getCheckType());
        copy.setValueType(source.getValueType());
        copy.setUnits(source.getUnits());
        copy.setDelaySeconds(source.getDelaySeconds());
        copy.setCustomIntervals(new ArrayList<>(source.getCustomIntervals()));
        copy.setHistoryDays(source.getHistoryDays());
        copy.setTrendDays(source.getTrendDays());
        copy.setDescription(source.getDescription());
        copy.setParams(new HashMap<>(source.getParams()));
        copy.setTimeoutSeconds(source.getTimeoutSeconds());
        copy.setFlags(source.getFlags());
        copy.setTemplateItem(source);

        // The interface is resolved at poll time from the host's own set, so
        // nothing is bound here: a template cannot know which interface a
        // particular host will have.
        for (ItemPreprocessing step : source.getPreprocessing()) {
            ItemPreprocessing stepCopy = new ItemPreprocessing();
            stepCopy.setItem(copy);
            stepCopy.setStep(step.getStep());
            stepCopy.setType(step.getType());
            stepCopy.setParams(new ArrayList<>(step.getParams()));
            stepCopy.setErrorHandler(step.getErrorHandler());
            stepCopy.setErrorHandlerParams(step.getErrorHandlerParams());
            copy.getPreprocessing().add(stepCopy);
        }

        return copy;
    }

    private TriggerDef copyTrigger(TriggerDef source, Host template, Host host) {
        String expression = rewriteExpression(
                source.getExpression(), template.getTechnicalName(), host.getTechnicalName());
        String recoveryExpression = rewriteExpression(
                source.getRecoveryExpression(), template.getTechnicalName(), host.getTechnicalName());

        TriggerDef copy = new TriggerDef();
        copy.setTenantId(host.getTenantId());
        copy.setHost(host);
        copy.setDescription(source.getDescription());
        copy.setExpression(expression);
        copy.setRecoveryMode(source.getRecoveryMode());
        copy.setRecoveryExpression(recoveryExpression);
        copy.setSeverity(source.getSeverity());
        copy.setStatus(source.getStatus());
        copy.setEventGeneration(source.getEventGeneration());
        copy.setManualClose(source.isManualClose());
        copy.setUrl(source.getUrl());
        copy.setComments(source.getComments());
        copy.setOpdata(source.getOpdata());
        copy.setEventName(source.getEventName());
        copy.setFlags(source.getFlags());
        copy.setTemplateTrigger(source);

        for (TriggerTag tag : source.getTags()) {
            TriggerTag tagCopy = new TriggerTag();
            tagCopy.setTriggerDef(copy);
            tagCopy.setTag(tag.getTag());
            tagCopy.setValue(tag.getValue());
            copy.getTags().add(tagCopy);
        }

        bindItems(copy, host);
        return copy;
    }

    /**
     * Rewrites {@code /template-name/key} to {@code /host-name/key}.
     *
     * <p>References to other hosts are left alone: a template trigger may
     * legitimately name a shared upstream device, and rewriting that would
     * point it at an item the host does not have.
     */
    static String rewriteExpression(String expression, String templateName, String hostName) {
        if (expression == null || expression.isBlank()) {
            return "";
        }
        return expression.replace("/" + templateName + "/", "/" + hostName + "/");
    }

    /**
     * Records which items the trigger reads.
     *
     * <p>Populated from the parsed expression so that evaluating a value later
     * is an index lookup rather than a scan over every trigger's text.
     */
    private void bindItems(TriggerDef trigger, Host host) {
        try {
            var parsed = ExpressionParser.parseWithReferences(trigger.getExpression());
            for (var reference : parsed.referencedItems()) {
                items.findByHostNameAndKey(host.getTenantId(), reference.host(), reference.key())
                        .ifPresent(item -> trigger.getItems().add(item));
            }
        } catch (ExpressionParser.ExpressionException e) {
            // A template shipping an unparseable expression is a defect in the
            // template, but it must not stop the rest of the link. The trigger
            // is created and will report the parse error against itself.
            log.warn("Trigger '{}' on host '{}' has an expression that does not parse: {}",
                    trigger.getDescription(), host.getTechnicalName(), e.getMessage());
        }
    }

    private Host findTemplate(Long tenantId, String templateName) {
        return hosts.findByTenantIdAndTechnicalName(tenantId, templateName)
                .or(() -> hosts.findTemplates(tenantId).stream()
                        .filter(template -> template.getName().equals(templateName))
                        .findFirst())
                .filter(candidate -> candidate.getFlags() == HostFlags.TEMPLATE)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No such template: '" + templateName + "'"));
    }
}
