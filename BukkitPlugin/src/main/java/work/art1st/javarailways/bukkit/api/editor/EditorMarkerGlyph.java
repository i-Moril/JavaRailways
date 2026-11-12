package work.art1st.javarailways.bukkit.api.editor;

/** Glyphs supplied by the independent editor marker resource pack. Arrow points along local +Y. */
public enum EditorMarkerGlyph {
    DIRECTION('\ue100'),
    BOUNDARY('\ue101'),
    COVERAGE('\ue102');

    private final char character;

    EditorMarkerGlyph(char character) { this.character = character; }

    public String text() { return Character.toString(character); }
}
