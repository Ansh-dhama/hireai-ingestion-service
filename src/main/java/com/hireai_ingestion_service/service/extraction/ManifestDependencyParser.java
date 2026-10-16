package com.hireai_ingestion_service.service.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.StringReader;
import java.util.*;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

@Component
public class ManifestDependencyParser {
  private final ObjectMapper json;

  public ManifestDependencyParser(ObjectMapper json) {
    this.json = json;
  }

  public List<Map<String, Object>> parse(String path, String content) {
    List<Map<String, Object>> result = new ArrayList<>();
    String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    try {
      if (name.equals("pom.xml")) {
        var f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setXIncludeAware(false);
        var builder = f.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
        var doc = builder.parse(new InputSource(new StringReader(content)));
        var nodes = doc.getElementsByTagName("dependency");
        for (int i = 0; i < nodes.getLength() && result.size() < 200; i++) {
          Element dep = (Element) nodes.item(i);
          String group = child(dep, "groupId"),
              artifact = child(dep, "artifactId"),
              version = child(dep, "version");
          if (!artifact.isBlank())
            add(
                result,
                "MAVEN",
                group + ":" + artifact,
                version,
                path,
                version.contains("${") || version.isBlank() ? "UNRESOLVED" : "DECLARED");
        }
      } else if (name.equals("package.json")) {
        var root = json.readTree(content);
        for (String section :
            List.of(
                "dependencies", "devDependencies", "peerDependencies", "optionalDependencies")) {
          var fields = root.path(section).fields();
          while (fields.hasNext() && result.size() < 200) {
            var e = fields.next();
            add(result, "NPM", e.getKey(), e.getValue().asText(), path, "DECLARED");
          }
        }
      } else if (name.startsWith("requirements") && name.endsWith(".txt")) {
        var re = Pattern.compile("^([A-Za-z0-9][A-Za-z0-9_.-]*)(?:\\[[^]]+])?\\s*(.*)$");
        for (String line : content.split("\\R")) {
          if (result.size() >= 200) break;
          line = line.strip();
          if (line.isEmpty() || line.startsWith("#")) continue;
          var m = re.matcher(line);
          if (m.matches() && !line.contains("://"))
            add(result, "PIP", m.group(1), m.group(2), path, "DECLARED");
          else add(result, "PIP", "[unsupported declaration]", "", path, "UNRESOLVED");
        }
      } else if (name.equals("build.gradle") || name.equals("build.gradle.kts")) {
        var m =
            Pattern.compile(
                    "(?:implementation|api|compileOnly|runtimeOnly|testImplementation|annotationProcessor)\\s*(?:\\(\\s*)?[\"']([^\"'\\s:]+):([^\"'\\s:]+):([^\"'\\s]+)[\"']")
                .matcher(content);
        while (m.find() && result.size() < 200)
          add(
              result,
              "GRADLE",
              m.group(1) + ":" + m.group(2),
              m.group(3),
              path,
              m.group(3).contains("$") ? "UNRESOLVED" : "DECLARED");
        add(
            result,
            "GRADLE",
            "[static-analysis limitation]",
            "",
            path,
            "DYNAMIC_BUILD_NOT_EXECUTED");
      }
    } catch (Exception error) {
      result.clear();
      add(result, "UNKNOWN", "[manifest parse failed]", "", path, "PARSE_FAILED");
    }
    return List.copyOf(result);
  }

  private String child(Element e, String name) {
    for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
      if (n instanceof Element x && x.getTagName().equals(name)) return x.getTextContent().strip();
    return "";
  }

  private void add(
      List<Map<String, Object>> rows,
      String ecosystem,
      String name,
      String version,
      String path,
      String resolution) {
    rows.add(
        Map.of(
            "ecosystem",
            ecosystem,
            "name",
            limit(name),
            "versionExpression",
            limit(version),
            "sourcePath",
            path,
            "resolution",
            resolution));
  }

  private String limit(String s) {
    return s.length() > 500 ? s.substring(0, 500) : s;
  }
}
