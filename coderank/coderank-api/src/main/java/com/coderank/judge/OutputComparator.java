package com.coderank.judge;

/**
 * Pure utility for exact-match comparison of program output against expected output.
 * Trailing whitespace on each line and trailing empty lines are stripped before
 * comparison so that minor formatting differences (e.g. trailing newline) do not
 * cause false WRONG_ANSWER verdicts.
 */
public class OutputComparator {

    private OutputComparator() {}

    /**
     * Compares actual output against expected output.
     * Trims trailing whitespace from each line and trailing empty lines
     * from both strings before comparing.
     *
     * @return true if the normalized strings match exactly
     */
    public static boolean matches(String actual, String expected) {
        if (actual == null && expected == null) return true;
        if (actual == null || expected == null) return false;
        return stripTrailing(actual).equals(stripTrailing(expected));
    }

    private static String stripTrailing(String s) {
        String[] lines = s.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(line.stripTrailing());
        }
        return sb.toString().stripTrailing();
    }
}
