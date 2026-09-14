package com.nms.server.repository;

/**
 * Marker for the repository package.
 *
 * <p>The repositories themselves are one interface per aggregate root; this
 * type exists only to give the package a place to hang documentation and to be
 * referenced by {@code @EnableJpaRepositories(basePackageClasses = ...)}, which
 * survives a package rename where a string literal would not.
 */
public final class SupportRepositories {

    private SupportRepositories() {
    }
}
