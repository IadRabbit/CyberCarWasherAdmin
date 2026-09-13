package com.meethack.romhack.camp2026;

import java.util.Locale;

/** Formats a whole-euro amount as a string (e.g. "€ 27"). */
public final class Money {

    private Money() {
    }

    public static String format(int amount) {
        return String.format(Locale.ITALY, "€ %d", amount);
    }
}
