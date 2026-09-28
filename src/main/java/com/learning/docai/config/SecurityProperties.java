package com.learning.docai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Resource server settings per SPEC §7. Deliberately without bean validation: the properties
 * are bound in every profile, including `local`, where no issuer exists and none is needed.
 * {@link SecurityConfig} checks them when it actually builds the secured chain.
 *
 * @param issuerUri    Azure Entra ID tenant, e.g. https://login.microsoftonline.com/<tenant>/v2.0
 * @param audience     the `aud` this API accepts, i.e. its own application id URI
 * @param requiredRole the Entra app role a caller must carry to reach /api/**
 */
@ConfigurationProperties(prefix = "docai.security")
public record SecurityProperties(
        String issuerUri,
        String audience,
        @DefaultValue("DocAi.Process") String requiredRole) {
}
