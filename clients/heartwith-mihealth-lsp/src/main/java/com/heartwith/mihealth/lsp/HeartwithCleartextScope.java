package com.heartwith.mihealth.lsp;

final class HeartwithCleartextScope {
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private HeartwithCleartextScope() {
    }

    static void enter() {
        ACTIVE.set(Boolean.TRUE);
    }

    static void exit() {
        ACTIVE.remove();
    }

    static boolean isActive() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }
}
