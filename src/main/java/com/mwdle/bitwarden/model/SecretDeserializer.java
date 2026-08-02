package com.mwdle.bitwarden.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import hudson.util.Secret;
import java.io.IOException;

/**
 * A custom Jackson deserializer that converts a JSON string directly into a Jenkins {@link Secret}.
 * <p>
 * This is used on fields in the model classes to ensure that any sensitive data read from the
 * Bitwarden CLI's JSON output is immediately wrapped in a {@link Secret} object and not
 * handled as a plain string in memory.
 */
public final class SecretDeserializer extends JsonDeserializer<Secret> {
    @Override
    public Secret deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        return p.getValueAsString() != null ? Secret.fromString(p.getValueAsString()) : null;
    }
}
