package com.mariia.javaapi.docs;

import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class EditableDocsService {

    /**
     * Generuje prosty HTML na podstawie wygenerowanego openapi.yaml.
     * Na razie: owinięty YAML w <pre>, żebyś miała co edytować w textarea.
     */
    public String renderHtmlFromYaml(Path yamlPath) {
        try {
            String yaml = Files.readString(yamlPath, StandardCharsets.UTF_8);
            String escaped = HtmlUtils.htmlEscape(yaml);
            return """
                    <html>
                      <head>
                        <meta charset="utf-8"/>
                        <title>API Docs</title>
                        <style>
                          body { font-family: system-ui, -apple-system, BlinkMacSystemFont, sans-serif; padding: 24px; }
                          pre { white-space: pre-wrap; word-wrap: break-word; }
                        </style>
                      </head>
                      <body>
                        <h1>OpenAPI spec (surowy YAML)</h1>
                        <pre>%s</pre>
                      </body>
                    </html>
                    """.formatted(escaped);
        } catch (IOException e) {
            throw new RuntimeException("Nie udało się odczytać YAML z " + yamlPath, e);
        }
    }

    /**
     * Na razie „udaje” PDF – zwraca bajty HTML.
     * Dzięki temu się kompiluje i możesz przetestować przepływ end-to-end,
     * a potem podmienimy implementację na prawdziwy generator PDF z HTML.
     */
    public byte[] renderPdfFromHtml(String html) {
        return html.getBytes(StandardCharsets.UTF_8);
    }
}
