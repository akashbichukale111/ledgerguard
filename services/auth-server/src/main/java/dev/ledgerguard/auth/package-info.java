/**
 * Authorization server.
 *
 * <p>Self-hosted Spring Authorization Server acting as the local OAuth2 issuer. Exists so
 * the stack has zero external or paid identity dependencies; every other service validates
 * its JWTs against this issuer JWKS.
 */
package dev.ledgerguard.auth;
