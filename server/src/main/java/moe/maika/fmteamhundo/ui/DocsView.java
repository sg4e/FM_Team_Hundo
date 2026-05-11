package moe.maika.fmteamhundo.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.markdown.Markdown;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEvent;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.HasUrlParameter;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.WildcardParameter;
import com.vaadin.flow.server.auth.AnonymousAllowed;

@Route("docs")
@AnonymousAllowed
public class DocsView extends VerticalLayout implements HasUrlParameter<String>, HasDynamicTitle {

    private static final Map<String, DocPage> DOCS = new LinkedHashMap<>();
    private String title = "Documentation";

    static {
        DOCS.put("rules", new DocPage("Rules", "docs/rules.md"));
        DOCS.put("setup", new DocPage("Before the Event: Getting Set Up", "docs/setup.md"));
        DOCS.put("playing", new DocPage("Ready to Play: Connecting Your Emulator", "docs/playing.md"));
        DOCS.put("tips", new DocPage("Helpful Tips", "docs/tips.md"));
    }

    private final VerticalLayout content;

    public DocsView() {
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        setDefaultHorizontalComponentAlignment(FlexComponent.Alignment.STRETCH);

        content = new VerticalLayout();
        content.setWidthFull();
        content.setPadding(true);
        content.setSpacing(true);
        content.addClassName("page-container");

        add(ViewSupport.createTopBar(), content);
    }

    @Override
    public void setParameter(BeforeEvent event, @WildcardParameter String parameter) {
        render(parameter == null ? "" : parameter.trim());

        // Scroll to top of the page
        UI.getCurrent().getPage().executeJs("window.scrollTo(0, 0)");
    }

    @Override
    public String getPageTitle() {
        return title;
    }

    private void render(String parameter) {
        content.removeAll();
        title = "Documentation";

        if(parameter.isEmpty()) {
            renderIndex();
            return;
        }

        DocPage docPage = DOCS.get(parameter);
        if(docPage == null) {
            renderNotFound();
            return;
        }
        title = docPage.title();
        content.add(createBackLink(), createMarkdown(docPage));
    }

    private void renderIndex() {
        content.add(new H1("Documentation"));

        UnorderedList docsList = new UnorderedList();
        DOCS.forEach((slug, docPage) -> docsList.add(new com.vaadin.flow.component.html.ListItem(
            new Anchor("/docs/" + slug, docPage.title())
        )));

        content.add(new Paragraph("Choose a document to view:"), docsList);
    }

    private void renderNotFound() {
        content.add(
            createBackLink(),
            new H1("Document not found"),
            new Paragraph("The requested documentation page does not exist.")
        );
    }

    private Component createBackLink() {
        Anchor backLink = new Anchor("/docs", "Back to Docs");
        backLink.addClassName("external-link");
        return backLink;
    }

    private Component createMarkdown(DocPage docPage) {
        try(InputStream inputStream = getClass().getClassLoader().getResourceAsStream(docPage.resourcePath())) {
            if(inputStream == null) {
                return new Paragraph("This document could not be loaded.");
            }

            Markdown markdown = new Markdown(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
            markdown.setWidthFull();
            return markdown;
        }
        catch(IOException ex) {
            return new Paragraph("This document could not be loaded.");
        }
    }

    private record DocPage(String title, String resourcePath) { }
}
