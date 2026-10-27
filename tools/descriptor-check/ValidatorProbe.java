import com.jetbrains.plugin.structure.base.problems.PluginProblem;
import com.jetbrains.plugin.structure.base.problems.ValidatorKt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calls the real JetBrains marketplace validator (structure-base ValidatorKt)
 * against a raw <description> body and reports whether the Latin-start rule passes.
 */
public final class ValidatorProbe {

    /** Exact copy of STARTS_WITH_LATIN_SYMBOLS_REGEX from ValidatorKt static init. */
    private static final Pattern RULE = Pattern.compile("^[\\w\\s\\p{Punct}\\u2013\\u2014]{40,}");

    public static void main(String[] args) throws IOException {
        Path file = Path.of(args[0]);
        String raw = Files.readString(file, StandardCharsets.UTF_8);

        System.out.println("file       = " + file);
        System.out.println("rawChars   = " + raw.length());
        System.out.println();

        List<PluginProblem> problems = ValidatorKt.validateDescriptionIsCorrect(
                "description", "description", raw);

        System.out.println("problems   = " + problems.size());
        for (PluginProblem p : problems) {
            System.out.println("  level=" + p.getLevel()
                    + "  type=" + p.getClass().getSimpleName()
                    + "  msg=" + p.getMessage());
        }

        String plain = org.jsoup.Jsoup.parseBodyFragment(raw).text();
        System.out.println();
        System.out.println("plainChars = " + plain.length());
        String head = plain.length() > 80 ? plain.substring(0, 80) : plain;
        System.out.println("plainHead  = " + head);

        Matcher m = RULE.matcher(plain);
        boolean ok = m.find();
        System.out.println("regexMatch = " + ok);
        if (ok) {
            System.out.println("matchedLen = " + m.group().length());
        } else {
            // how many ASCII chars the rule could consume before the first non-ASCII char
            int run = 0;
            for (int i = 0; i < plain.length(); i++) {
                char c = plain.charAt(i);
                if (c < 128) {
                    run++;
                } else {
                    break;
                }
            }
            System.out.println("asciiRunAtStart = " + run + " (need >= 40)");
        }
        System.out.println();
        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL");
    }
}
