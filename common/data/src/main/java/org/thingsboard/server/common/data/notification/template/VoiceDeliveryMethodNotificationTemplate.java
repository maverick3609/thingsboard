// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.common.data.notification.template;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.thingsboard.server.common.data.notification.NotificationDeliveryMethod;
import org.thingsboard.server.common.data.validation.Length;
import org.thingsboard.server.common.data.validation.NoXss;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A phone call. Only the English {@code body} is written by hand. The Hindi text is
 * machine-translated once, in the template editor, then stored here and editable; nothing is
 * translated per call. {@code localizedSource} is the English they were translated from, so the
 * editor can tell when the English changed afterwards.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class VoiceDeliveryMethodNotificationTemplate extends DeliveryMethodNotificationTemplate {

    public static final int MAX_LENGTH = 600;
    public static final List<String> TRANSLATED_LANGUAGES = List.of("hi");

    private Map<String, String> localizedBodies = new HashMap<>();
    @NoXss(fieldName = "Voice translation source")
    @Length(fieldName = "Voice translation source", max = MAX_LENGTH, message = "cannot be longer than 600 chars")
    private String localizedSource;
    private boolean ackRequired;

    public VoiceDeliveryMethodNotificationTemplate(VoiceDeliveryMethodNotificationTemplate other) {
        super(other);
        this.localizedBodies = other.localizedBodies == null ? new HashMap<>() : new HashMap<>(other.localizedBodies);
        this.localizedSource = other.localizedSource;
        this.ackRequired = other.ackRequired;
    }

    @NoXss(fieldName = "Voice message")
    @Length(fieldName = "Voice message", max = MAX_LENGTH, message = "cannot be longer than 600 chars")
    @Override
    public String getBody() {
        return super.getBody();
    }

    @JsonIgnore
    @AssertTrue(message = "Voice translations must be Hindi (hi), at most 600 chars each")
    public boolean isLocalizedBodiesValid() {
        return localizedBodies == null || localizedBodies.entrySet().stream().allMatch(e ->
                TRANSLATED_LANGUAGES.contains(e.getKey()) && (e.getValue() == null || e.getValue().length() <= MAX_LENGTH));
    }

    /** The translations as one string, so the XSS check that guards {@code body} covers them: it skips maps. */
    @JsonIgnore
    @NoXss(fieldName = "Voice translation")
    public String getLocalizedText() {
        return localizedBodies == null ? "" : localizedBodies.values().stream()
                .filter(Objects::nonNull).collect(Collectors.joining("\n"));
    }

    /** Built on each call, so every value writes into this instance: TB fills placeholders in a copy. */
    @JsonIgnore
    @Override
    public List<TemplatableValue> getTemplatableValues() {
        List<TemplatableValue> values = new ArrayList<>();
        values.add(TemplatableValue.of(this::getBody, this::setBody));
        if (localizedBodies != null) {
            for (String language : new ArrayList<>(localizedBodies.keySet())) {
                values.add(TemplatableValue.of(() -> localizedBodies.get(language), text -> localizedBodies.put(language, text)));
            }
        }
        return values;
    }

    @Override
    public NotificationDeliveryMethod getMethod() {
        return NotificationDeliveryMethod.VOICE;
    }

    @Override
    public DeliveryMethodNotificationTemplate copy() {
        return new VoiceDeliveryMethodNotificationTemplate(this);
    }

}
