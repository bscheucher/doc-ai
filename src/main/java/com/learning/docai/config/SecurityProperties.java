package com.learning.docai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The one resource server setting that is ours (SPEC §7). Issuer and audience are named by the
 * spec as Spring's own keys, so {@link SecurityConfig} reads them from Boot's
 * {@code OAuth2ResourceServerProperties} rather than binding them a second time here; the app
 * role is specific to this service and has no Spring key to borrow.
 *
 * <p>Deliberately without bean validation: the property is bound in every profile, including
 * `local`, where no resource server is built. {@link SecurityConfig} checks it when it actually
 * builds the secured chain.
 *
 * @param requiredRole the Entra app role a caller must carry to reach /api/**
 */
@ConfigurationProperties(prefix = "docai.security")
public record SecurityProperties(@DefaultValue("DocAi.Process") String requiredRole) {
}
