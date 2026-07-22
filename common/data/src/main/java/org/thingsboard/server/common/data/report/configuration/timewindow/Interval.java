/**
 * Copyright © 2016-2026 The Inferrix Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.common.data.report.configuration.timewindow;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.thingsboard.server.common.data.kv.IntervalType;

import java.io.IOException;

@Data
@NoArgsConstructor
@JsonDeserialize(using = Interval.IntervalDeserializer.class)
@JsonSerialize(using = Interval.IntervalSerializer.class)
public class Interval {

    private long interval;
    private IntervalType intervalType;

    public static Interval of(long intervalLong) {
        Interval interval = new Interval();
        interval.setIntervalType(IntervalType.MILLISECONDS);
        interval.setInterval(intervalLong);
        return interval;
    }

    public static Interval of(IntervalType intervalType) {
        Interval interval = new Interval();
        interval.setInterval(0L);
        interval.setIntervalType(intervalType);
        return interval;
    }

    public static class IntervalSerializer extends JsonSerializer<Interval> {
        @Override
        public void serialize(Interval value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            if (value.intervalType != null && !IntervalType.MILLISECONDS.equals(value.intervalType)) {
                gen.writeString(value.intervalType.name());
            } else {
                gen.writeNumber(value.interval);
            }
        }
    }

    public static class IntervalDeserializer extends JsonDeserializer<Interval> {
        static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

        @Override
        public Interval deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException, JacksonException {
            JsonToken token = jsonParser.currentToken();
            if (token == JsonToken.VALUE_STRING) {
                String value = jsonParser.getText();
                try {
                    IntervalType intervalType = IntervalType.valueOf(value);
                    return Interval.of(intervalType);
                } catch (IllegalArgumentException e) {
                    throw new IOException("Unknown Interval type: " + value);
                }
            }
            if (token == JsonToken.VALUE_NUMBER_INT) {
                long value = jsonParser.getLongValue();
                return Interval.of(value);
            }
            return new Interval();
        }
    }
}
