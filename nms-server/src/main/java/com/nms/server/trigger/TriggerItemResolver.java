package com.nms.server.trigger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nms.server.domain.Item;
import com.nms.server.domain.TriggerDef;
import com.nms.server.repository.ItemRepository;
import com.nms.server.trigger.expression.ExpressionNode.ItemReference;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the {@code /host/key} references inside a trigger to stored items.
 *
 * <p>The mapping is cached per trigger, because it changes only when the
 * expression is edited but is needed on every evaluation.
 */
@Component
public class TriggerItemResolver implements TriggerEvaluator.ItemReferenceResolver {

    private final ItemRepository items;

    private final Cache<Long, Map<ItemReference, HistoryFunctionContext.ResolvedItem>> cache =
            Caffeine.newBuilder()
                    .maximumSize(100_000)
                    .expireAfterWrite(Duration.ofMinutes(5))
                    .build();

    public TriggerItemResolver(ItemRepository items) {
        this.items = items;
    }

    @Override
    public HistoryFunctionContext.ItemLookup lookupFor(TriggerDef trigger) {
        Map<ItemReference, HistoryFunctionContext.ResolvedItem> resolved =
                cache.get(trigger.getId(), id -> buildMapping(trigger));
        return HistoryFunctionContext.ItemLookup.of(resolved);
    }

    /**
     * Builds the reference map from the trigger's recorded item associations.
     *
     * <p>Those associations are written by the parser when the trigger is
     * saved, so this normally needs no query at all. The repository is only
     * consulted for a reference the association table does not cover -- which
     * happens when an expression names an item on another host that was
     * created after the trigger.
     */
    private Map<ItemReference, HistoryFunctionContext.ResolvedItem> buildMapping(TriggerDef trigger) {
        Map<ItemReference, HistoryFunctionContext.ResolvedItem> mapping = new HashMap<>();

        for (Item item : trigger.getItems()) {
            ItemReference reference =
                    new ItemReference(item.getHost().getTechnicalName(), item.getKey());
            mapping.put(reference,
                    new HistoryFunctionContext.ResolvedItem(item.getId(), item.getValueType(), reference));
        }
        return mapping;
    }

    /**
     * Looks an item up by host name and key.
     *
     * <p>Used when saving a trigger, to turn the parsed references into the
     * associations that make evaluation an index lookup.
     */
    public Optional<HistoryFunctionContext.ResolvedItem> resolve(Long tenantId, ItemReference reference) {
        return items.findByHostNameAndKey(tenantId, reference.host(), reference.key())
                .map(item -> new HistoryFunctionContext.ResolvedItem(
                        item.getId(), item.getValueType(), reference));
    }

    /** Drops a cached mapping after the trigger's expression is edited. */
    public void invalidate(long triggerId) {
        cache.invalidate(triggerId);
    }
}
