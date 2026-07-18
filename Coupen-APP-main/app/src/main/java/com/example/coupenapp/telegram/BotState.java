package com.example.coupenapp.telegram;

/** Lifecycle states of the Telegram listener, shown in the Bot Control UI. */
public enum BotState {
    STOPPED("Stopped"),
    CONNECTING("Connecting"),
    RUNNING("Running"),
    RECONNECTING("Reconnecting"),
    ERROR("Error");

    private final String label;

    BotState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
