package com.wxn.reader.presentation.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Checks translator-facing format contracts without requiring an Android runtime. */
public class LocalizedStringFormatTest {
    private static final Pattern FORMAT = Pattern.compile(
            "%(?:(\\d+)\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?([a-zA-Z%])");

    @Test
    public void translatedFormatsKeepTheDefaultArgumentIndexesAndTypes() throws Exception {
        List<File> files = resourceFiles();
        Map<String, String> defaults = strings(new File(resourceDirectory(), "values/strings.xml"));
        for (File file : files) {
            for (Map.Entry<String, String> entry : strings(file).entrySet()) {
                String name = entry.getKey();
                String base = defaults.get(name);
                if (base != null && (base.contains("%") || entry.getValue().contains("%"))) {
                    assertEquals(file.getParentFile().getName() + "/" + name,
                            arguments(base), arguments(entry.getValue()));
                }
            }
        }
    }

    @Test
    public void importProgressFormatsBothCountsInEveryLanguage() throws Exception {
        for (File file : resourceFiles()) {
            String format = strings(file).get("adding_books_num");
            String label = file.getParentFile().getName();
            assertEquals(label, Map.of(1, "d", 2, "d"), arguments(format));
            String message = String.format(Locale.ROOT, format, 3, 12);
            assertTrue(label + ": " + message, message.contains("3/12"));
        }
    }

    @Test
    public void deleteFailureFormatsTheTitleAndReasonInEveryLanguage() throws Exception {
        for (File file : resourceFiles()) {
            String format = strings(file).get("failed_delete_book_info");
            String label = file.getParentFile().getName();
            assertEquals(label, Map.of(1, "s", 2, "s"), arguments(format));
            String message = String.format(Locale.ROOT, format, "Example Book", "Permission denied");
            assertTrue(label + ": " + message, message.contains("Example Book"));
            assertTrue(label + ": " + message, message.contains("Permission denied"));
        }
    }

    private static Map<Integer, String> arguments(String format) {
        Map<Integer, String> arguments = new TreeMap<>();
        Matcher matcher = FORMAT.matcher(format);
        while (matcher.find()) {
            if (matcher.group(2).equals("%") || matcher.group(2).equals("n")) continue;
            assertTrue("Format arguments must have explicit indexes: " + format,
                    matcher.group(1) != null);
            int index = Integer.parseInt(matcher.group(1));
            String previous = arguments.put(index, matcher.group(2));
            if (previous != null) assertEquals(format, previous, matcher.group(2));
        }
        assertFalse("Malformed format string: " + format, matcher.replaceAll("").contains("%"));
        return arguments;
    }

    private static Map<String, String> strings(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        NodeList nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("string");
        Map<String, String> strings = new TreeMap<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            Element node = (Element) nodes.item(index);
            if (!"false".equals(node.getAttribute("formatted"))) {
                strings.put(node.getAttribute("name"), node.getTextContent());
            }
        }
        return strings;
    }

    private static List<File> resourceFiles() {
        File[] directories = resourceDirectory().listFiles(
                file -> file.isDirectory() && (file.getName().equals("values")
                        || file.getName().startsWith("values-")));
        assertTrue("Android values directories must be available", directories != null);
        Arrays.sort(directories);
        List<File> files = new ArrayList<>();
        for (File directory : directories) {
            File file = new File(directory, "strings.xml");
            if (file.isFile()) files.add(file);
        }
        assertFalse("String resource files must be available", files.isEmpty());
        return files;
    }

    private static File resourceDirectory() {
        File directory = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (directory != null && !new File(directory, "settings.gradle.kts").isFile()) {
            directory = directory.getParentFile();
        }
        assertTrue("Run from within the repository", directory != null);
        return new File(directory, "app/src/main/res");
    }
}
