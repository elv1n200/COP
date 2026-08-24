package cop.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Ordered mutation buffer used while the chat search view temporarily owns
 * ChatComponent's message list. Additions and deletions must be replayed in
 * arrival order: a delete following a buffered add must delete that add, not
 * let it reappear when search closes.
 */
public final class ChatSearchBuffer<M, D> {
    private sealed interface Operation<M, D> permits Message, Deletion { }

    private record Message<M, D>(M value, int copId) implements Operation<M, D> { }
    private record Deletion<M, D>(D value) implements Operation<M, D> { }

    private final List<Operation<M, D>> operations = new ArrayList<>();

    public void queueMessage(M message, int copId) {
        operations.add(new Message<>(message, copId));
    }

    public void queueDeletion(D deletion) {
        operations.add(new Deletion<>(deletion));
    }

    public void clear() {
        operations.clear();
    }

    public int size() {
        return operations.size();
    }

    /**
     * Drains a stable snapshot. Clearing before callbacks means a callback
     * that queues a new mutation cannot have that mutation accidentally
     * removed by the current replay. If a callback fails, the failed operation
     * and the untouched suffix are prepended to mutations queued by callbacks,
     * preserving their original arrival order for a later retry.
     */
    public void drain(
            BiConsumer<? super M, Integer> messageConsumer,
            Consumer<? super D> deletionConsumer
    ) {
        List<Operation<M, D>> pending = List.copyOf(operations);
        operations.clear();

        for (int index = 0; index < pending.size(); index++) {
            Operation<M, D> operation = pending.get(index);
            try {
                if (operation instanceof Message<?, ?> message) {
                    @SuppressWarnings("unchecked")
                    M value = (M) message.value();
                    messageConsumer.accept(value, message.copId());
                } else if (operation instanceof Deletion<?, ?> deletion) {
                    @SuppressWarnings("unchecked")
                    D value = (D) deletion.value();
                    deletionConsumer.accept(value);
                }
            } catch (RuntimeException | Error failure) {
                List<Operation<M, D>> queuedDuringReplay = List.copyOf(operations);
                operations.clear();
                operations.addAll(pending.subList(index, pending.size()));
                operations.addAll(queuedDuringReplay);
                throw failure;
            }
        }
    }
}
