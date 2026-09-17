package com.habitrain.lottery.mail;

import java.util.ArrayList;
import java.util.List;

public record MailDraft(
        String sender,
        String title,
        String content,
        long expiresAt,
        List<MailReward> rewards
) {
    public MailDraft {
        if (sender == null || sender.isBlank()) {
            sender = "系统";
        }
        if (title == null) {
            title = "";
        }
        if (content == null) {
            content = "";
        }
        rewards = rewards == null ? List.of() : List.copyOf(rewards);
    }

    public List<MailReward> mutableRewards() {
        return new ArrayList<>(rewards);
    }
}
