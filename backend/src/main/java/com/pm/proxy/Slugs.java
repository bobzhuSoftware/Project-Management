package com.pm.proxy;

import java.text.Normalizer;
import java.util.Set;

/** Turns human names into stable, DNS-safe slugs for {@code <alias>.localhost} addresses. */
public final class Slugs {

    private Slugs() {}

    /**
     * Lowercases, strips accents, and collapses any run of non {@code [a-z0-9]} into a single
     * hyphen. Returns an empty string when the input has no usable ASCII letters/digits
     * (e.g. a purely CJK name) — callers should fall back to something id-derived.
     */
    public static String slugify(String raw) {
        if (raw == null) return "";
        String norm = Normalizer.normalize(raw, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");
        StringBuilder sb = new StringBuilder(norm.length());
        boolean prevHyphen = false;
        for (int i = 0; i < norm.length(); i++) {
            char c = Character.toLowerCase(norm.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                prevHyphen = false;
            } else if (!prevHyphen && sb.length() > 0) {
                sb.append('-');
                prevHyphen = true;
            }
        }
        // Trim a trailing hyphen.
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == '-') end--;
        return sb.substring(0, end);
    }

    /**
     * Produces a slug from {@code base}, guaranteeing it is non-empty and not already in
     * {@code taken}. Appends {@code -2}, {@code -3}, … on collision; falls back to
     * {@code fallback} when the base slugifies to empty.
     */
    public static String uniqueSlug(String base, String fallback, Set<String> taken) {
        String slug = slugify(base);
        if (slug.isEmpty()) slug = slugify(fallback);
        if (slug.isEmpty()) slug = "app";
        if (!taken.contains(slug)) return slug;
        for (int i = 2; ; i++) {
            String candidate = slug + "-" + i;
            if (!taken.contains(candidate)) return candidate;
        }
    }
}
