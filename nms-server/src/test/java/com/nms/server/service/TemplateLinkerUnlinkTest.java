package com.nms.server.service;

import com.nms.server.domain.Host;
import com.nms.server.domain.HostFlags;
import com.nms.server.domain.Item;
import com.nms.server.domain.TriggerDef;
import com.nms.server.repository.HostRepository;
import com.nms.server.repository.ItemRepository;
import com.nms.server.repository.TriggerRepository;
import com.nms.server.trigger.ProblemService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unlinking one template must leave the others alone.
 *
 * <p>The fault this guards is quiet and destructive in the same breath.
 * Derived triggers were selected by "came from some template" rather than
 * "came from this one", so removing a template from a host deleted every
 * template-derived trigger on it. On a camera carrying both the reachability
 * and the storage template, unlinking either one left the storage items
 * collecting perfectly and nothing at all watching them -- an SD card could
 * then fail in silence, which is the exact failure the storage template was
 * added to catch.
 *
 * <p>It is also the sort of thing no one notices by using the product: the
 * screen shows one template gone, which is what was asked for.
 */
class TemplateLinkerUnlinkTest {

    private static final long TENANT = 1L;
    private static final long HOST = 5L;
    private static final long TEMPLATE_A = 10L;
    private static final long TEMPLATE_B = 20L;

    private final HostRepository hosts = mock(HostRepository.class);
    private final ItemRepository items = mock(ItemRepository.class);
    private final TriggerRepository triggers = mock(TriggerRepository.class);
    private final ProblemService problems = mock(ProblemService.class);

    private final TemplateLinker linker = new TemplateLinker(hosts, items, triggers, problems);

    private final Host templateA = template(TEMPLATE_A, "template.a", "Template A");
    private final Host templateB = template(TEMPLATE_B, "template.b", "Template B");
    private final Host host = host();

    private final Item templateItemA = item(100L, templateA, "a.key", null);
    private final Item templateItemB = item(110L, templateB, "b.key", null);
    private final Item hostItemA = item(300L, host, "a.key", templateItemA);
    private final Item hostItemB = item(310L, host, "b.key", templateItemB);

    private final TriggerDef templateTriggerA = trigger(200L, templateA, "A is down", null);
    private final TriggerDef templateTriggerB = trigger(210L, templateB, "B is down", null);
    private final TriggerDef hostTriggerA = trigger(400L, host, "A is down", templateTriggerA);
    private final TriggerDef hostTriggerB = trigger(410L, host, "B is down", templateTriggerB);

    TemplateLinkerUnlinkTest() {
        host.getTemplates().add(templateA);
        host.getTemplates().add(templateB);

        when(items.findByHostId(TEMPLATE_A)).thenReturn(List.of(templateItemA));
        when(items.findByHostId(TEMPLATE_B)).thenReturn(List.of(templateItemB));
        when(items.findByHostId(HOST)).thenReturn(List.of(hostItemA, hostItemB));

        when(triggers.findByHostId(TEMPLATE_A)).thenReturn(List.of(templateTriggerA));
        when(triggers.findByHostId(TEMPLATE_B)).thenReturn(List.of(templateTriggerB));
        when(triggers.findByHostId(HOST)).thenReturn(List.of(hostTriggerA, hostTriggerB));

        when(hosts.findByTenantIdAndTechnicalName(TENANT, "Template B"))
                .thenReturn(Optional.of(templateB));
        // The host keeps its own copies, so the re-link pass finds them and
        // leaves them as they are rather than creating duplicates.
        when(items.findByHostIdAndKey(anyLong(), anyString())).thenAnswer(invocation ->
                List.of(hostItemA, hostItemB).stream()
                        .filter(item -> item.getKey().equals(invocation.getArgument(1)))
                        .findFirst());
    }

    @Test
    @DisplayName("unlinking one template deletes only the triggers it created")
    void unlinkingOneTemplateSparesTheOther() {
        linker.relink(host, List.of("Template B"));

        ArgumentCaptor<Iterable<TriggerDef>> deleted = ArgumentCaptor.captor();
        verify(triggers).deleteAll(deleted.capture());

        assertThat(deleted.getValue())
                .as("only template A's trigger is removed")
                .containsExactly(hostTriggerA);
    }

    @Test
    void unlinkingOneTemplateDeletesOnlyItsOwnItems() {
        linker.relink(host, List.of("Template B"));

        ArgumentCaptor<Iterable<Item>> deleted = ArgumentCaptor.captor();
        verify(items).deleteAll(deleted.capture());

        assertThat(deleted.getValue()).containsExactly(hostItemA);
    }

    /**
     * A problem records its trigger as a plain id with no foreign key, so
     * deleting the trigger does not touch it. An unlinked template therefore
     * used to leave its open problems on the Problems page permanently --
     * nothing could recover them, because the trigger that would have gone
     * back to OK no longer existed -- and any escalation already running
     * against them carried on notifying.
     */
    @Test
    @DisplayName("open problems are closed before their trigger is deleted")
    void closesTheProblemsOfDeletedTriggers() {
        linker.relink(host, List.of("Template B"));

        verify(problems).resolveProblems(eq(hostTriggerA), any(Instant.class));
        verify(problems, never()).resolveProblems(eq(hostTriggerB), any(Instant.class));
    }

    @Test
    void theUnlinkedTemplateIsTheOnlyOneDropped() {
        linker.relink(host, List.of("Template B"));

        assertThat(host.getTemplates()).containsExactly(templateB);
    }

    private static Host template(long id, String technicalName, String name) {
        Host template = new Host();
        template.setId(id);
        template.setTenantId(TENANT);
        template.setTechnicalName(technicalName);
        template.setName(name);
        template.setFlags(HostFlags.TEMPLATE);
        return template;
    }

    private static Host host() {
        Host created = new Host();
        created.setId(HOST);
        created.setTenantId(TENANT);
        created.setTechnicalName("cam-lobby-01");
        created.setName("Lobby camera");
        created.setFlags(HostFlags.MONITORED);
        return created;
    }

    private static Item item(long id, Host owner, String key, Item templateItem) {
        Item created = new Item();
        created.setId(id);
        created.setTenantId(TENANT);
        created.setHost(owner);
        created.setKey(key);
        created.setName(key);
        created.setTemplateItem(templateItem);
        return created;
    }

    private static TriggerDef trigger(long id, Host owner, String description, TriggerDef source) {
        TriggerDef created = new TriggerDef();
        created.setId(id);
        created.setTenantId(TENANT);
        created.setHost(owner);
        created.setDescription(description);
        created.setExpression("last(/" + owner.getTechnicalName() + "/icmpping)=0");
        created.setTemplateTrigger(source);
        return created;
    }
}
