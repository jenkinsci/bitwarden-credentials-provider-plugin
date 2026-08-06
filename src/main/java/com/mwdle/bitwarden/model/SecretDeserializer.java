package com.mwdle.bitwarden.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.util.Secret;
import java.io.IOException;

/**
 * Deserializes JSON string values directly into Jenkins {@link Secret} instances.
 */
public final class SecretDeserializer extends StdDeserializer<Secret> {

    public SecretDeserializer() {
        super(Secret.class);
    }

    @Override
    @NonNull
    public Secret deserialize(@NonNull JsonParser p, @NonNull DeserializationContext ctxt) throws IOException {
        return Secret.fromString(_parseString(p, ctxt, this));
    }
}
