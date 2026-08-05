package com.mwdle.bitwarden.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import hudson.util.Secret;
import java.io.IOException;

/**
 * Deserializes JSON string values directly into Jenkins {@link Secret} instances.
 */
public final class SecretDeserializer extends JsonDeserializer<Secret> {
    @Override
    public Secret deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        return p.getValueAsString() != null ? Secret.fromString(p.getValueAsString()) : null;
    }
}
