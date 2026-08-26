package dev.twme.sculpt.render;

/** Cancellable lifecycle handle for one incremental TextDisplay render state. */
public interface TextBlockRenderHandle {

    void despawn();

    boolean isCancelled();

    int entityCount();
}
