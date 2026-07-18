package com.example.coupenapp.telegram;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a coupon code from a raw Telegram message.
 *
 * <p>Default strategy (tune once real sample messages are available):
 * <ol>
 *   <li>If the whole message is a single code-like token, use it.</li>
 *   <li>If the text is labelled ("code: XXXX", "gift XXXX"), take that token.</li>
 *   <li>Otherwise take the first alphanumeric token that contains a digit
 *       (coupon codes usually do), else the first token.</li>
 * </ol>
 * Returns {@code null} when nothing looks like a code (message is ignored).
 */
public class CouponExtractor {

    private static final Pattern WHOLE     = Pattern.compile("[A-Za-z0-9]{4,64}");
    private static final Pattern LABELLED  = Pattern.compile(
            "(?i)(?:code|coupon|gift|redeem|voucher)\\s*[:\\-]?\\s*([A-Za-z0-9]{4,64})");
    private static final Pattern TOKEN     = Pattern.compile("\\b([A-Za-z0-9]{4,64})\\b");

    public static String extract(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;

        // 1) whole message is a single token
        if (WHOLE.matcher(t).matches()) return t;

        // 2) labelled "code: XXXX"
        Matcher m = LABELLED.matcher(t);
        if (m.find()) return m.group(1);

        // 3) first token that contains a digit
        Matcher tok = TOKEN.matcher(t);
        while (tok.find()) {
            String cand = tok.group(1);
            if (cand.matches(".*\\d.*")) return cand;
        }

        // 4) fallback: first alphanumeric token
        tok = TOKEN.matcher(t);
        if (tok.find()) return tok.group(1);

        return null;
    }
}
