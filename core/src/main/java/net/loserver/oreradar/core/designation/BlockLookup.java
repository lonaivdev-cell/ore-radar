package net.loserver.oreradar.core.designation;

/**
 * A platform's block registry, as {@link TargetRules} needs it. {@code B} is the platform's block
 * type ({@code Block} on the mods, {@code Material} on Paper); it must have identity-like equality.
 * Called only while building rules (at start and on reload), on the server thread.
 */
public interface BlockLookup<B> {
    /** Every registered block, in registry order. */
    Iterable<B> blocks();

    /** The block with this {@code ns:path} id, or null if there is none. */
    B block(String id);

    /** {@code ns:path} */
    String id(B block);

    /** The members of a block tag; empty if the tag is unknown. */
    Iterable<B> tag(String tagId);

    /** The ids ({@code ns:path}) of every known block tag, for auto ore groups. */
    Iterable<String> tagIds();

    /** Air, cave air, void air. */
    boolean isAir(B block);

    /** Whether the block has an item form (water, for one, does not). */
    boolean hasItem(B block);

    /** The block's translation key, e.g. {@code block.minecraft.iron_ore}. */
    String nameKey(B block);
}
