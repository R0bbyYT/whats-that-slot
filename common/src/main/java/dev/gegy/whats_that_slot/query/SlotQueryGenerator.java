package dev.gegy.whats_that_slot.query;

import com.google.common.collect.Iterators;
import dev.gegy.whats_that_slot.WhatsThatSlot;
import dev.gegy.whats_that_slot.collection.ConcatList;
import dev.gegy.whats_that_slot.collection.LazyFillingList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public final class SlotQueryGenerator {
    private State state;
    private SlotQuery results;

    public SlotQueryGenerator(Inventory inventory, Predicate<ItemStack> filter) {
        this.state = new MatchingInventory(inventory, filter);
    }

    @Nullable
    public SlotQuery advance(BooleanSupplier shouldContinue) {
        if (this.results != null) {
            return this.results;
        }

        var state = this.state;
        while (shouldContinue.getAsBoolean()) {
            this.state = state = state.advance(shouldContinue);
            if (state instanceof Ready ready) {
                return this.results = ready.results();
            }
        }

        return null;
    }

    public void advanceFor(Duration duration) {
        if (this.results == null) {
            long targetTime = System.nanoTime() + duration.toNanos();
            this.advance(() -> System.nanoTime() < targetTime);
        }
    }

    public SlotQuery build() {
        var query = this.advance(() -> true);
        return Objects.requireNonNull(query);
    }

    private interface State {
        State advance(BooleanSupplier shouldContinue);
    }

    private static final class MatchingInventory implements State {
        private final Predicate<ItemStack> filter;
        private final Iterator<ItemStack> inventoryIterator;

        private final Set<ItemStack> inventoryMatches = new ItemStackLinkedSet();

        private MatchingInventory(Inventory inventory, Predicate<ItemStack> filter) {
            this.filter = filter;
            this.inventoryIterator = InventoryItemStacks.iterator(inventory);
        }

        @Override
        public State advance(BooleanSupplier shouldContinue) {
            var inventory = this.inventoryIterator;
            while (shouldContinue.getAsBoolean() && inventory.hasNext()) {
                var item = inventory.next();
                if (this.filter.test(item)) {
                    this.addInventoryMatch(item);
                }
            }

            if (!inventory.hasNext()) {
                return new MatchingGlobal(this.filter, this.inventoryMatches);
            } else {
                return this;
            }
        }

        private void addInventoryMatch(ItemStack item) {
            item = item.copy();

            if (item.getCount() > 1) {
                item.setCount(1);
            }

            this.inventoryMatches.add(item);
        }
    }

    private static final class MatchingGlobal implements State {
        private final Predicate<ItemStack> globalFilter;
        private final Set<ItemStack> inventoryMatches;

        private final GlobalItemStacks globalItems = WhatsThatSlot.GLOBAL_ITEMS;
        private final Iterator<ItemStack> globalItemIterator = this.globalItems.iterator();

        private int matchCount;

        private MatchingGlobal(Predicate<ItemStack> filter, Set<ItemStack> inventoryMatches) {
            this.globalFilter = filter.and(item -> !inventoryMatches.contains(item));
            this.inventoryMatches = inventoryMatches;
        }

        @Override
        public State advance(BooleanSupplier shouldContinue) {
            var iterator = this.globalItemIterator;
            while (shouldContinue.getAsBoolean() && iterator.hasNext()) {
                var item = iterator.next();
                if (this.globalFilter.test(item)) {
                    this.matchCount++;
                }
            }

            if (!iterator.hasNext()) {
                SlotQuery results = this.buildResults();
                return new Ready(results);
            } else {
                return this;
            }
        }

        private SlotQuery buildResults() {
            return new SlotQuery(ConcatList.of(
                    this.buildInventoryResults(),
                    this.buildGlobalResults()
            ));
        }

        private LazyFillingList<QueriedItem> buildGlobalResults() {
            return LazyFillingList.ofIterator(
                    Iterators.transform(
                            Iterators.filter(this.globalItems.iterator(), this.globalFilter::test),
                            QueriedItem::of
                    ),
                    this.matchCount
            );
        }

        private ObjectArrayList<QueriedItem> buildInventoryResults() {
            var inventoryItems = new ObjectArrayList<QueriedItem>(this.inventoryMatches.size());
            for (var match : this.inventoryMatches) {
                inventoryItems.add(QueriedItem.ofHighlighted(match));
            }
            return inventoryItems;
        }
    }

    private record Ready(SlotQuery results) implements State {
        @Override
        public State advance(BooleanSupplier shouldContinue) {
            return this;
        }
    }
}
