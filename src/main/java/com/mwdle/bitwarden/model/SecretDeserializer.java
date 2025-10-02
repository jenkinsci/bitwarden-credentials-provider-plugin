package com.mwdle.bitwarden.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import hudson.util.Secret;
import java.io.IOException;

/**
 * Deserializes a JSON string into a Jenkins {@link Secret}.
 * <p>
 * This is intended for deserializing sensitive values (e.g., secure note content) so they
 * are stored as {@link Secret} rather than plain strings.
 * Converts the raw string value to a Secret via {@link Secret#fromString(String)}.
 */
public class SecretDeserializer extends JsonDeserializer<Secret> {
    @Override
    public Secret deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        return p.getValueAsString() != null ? Secret.fromString(p.getValueAsString()) : null;
    }
}
