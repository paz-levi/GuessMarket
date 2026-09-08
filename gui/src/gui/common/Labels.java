package gui.common;

import javafx.scene.control.Label;

// The two Label shapes this app builds over and over. Public and controller-free so a separate module can build
// the same-looking UI without copying either idiom.
public final class Labels {

    private static final String SECTION_HEADER_STYLE_CLASS = "section-header";

    private Labels() {
    }

    // A plain Label's minimum width equals its full unwrapped text width, so a long line simply clips once its
    // container is squeezed narrower by a resize -- wrapText lets it reflow onto more lines instead, which
    // CLAUDE.md's resize rule requires. Use for any label whose text isn't short and bounded.
    public static Label wrapping(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        return label;
    }

    // A bolded section title, styled by the .section-header rule every stylesheet defines. Deliberately NOT
    // wrapping: section headers are short, bounded text, and the resize work left those alone on purpose.
    public static Label sectionHeader(String text) {
        Label label = new Label(text);
        label.getStyleClass().add(SECTION_HEADER_STYLE_CLASS);
        return label;
    }
}
