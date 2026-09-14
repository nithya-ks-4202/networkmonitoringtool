package com.nms.server.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the repository declarations against misuse that only shows up at
 * runtime.
 *
 * <p>Spring Data builds its queries lazily, so a badly declared one compiles,
 * starts, passes a health check and then throws the first time it is called.
 * The scheduler's claim query was declared with both {@code @Lock} and
 * {@code nativeQuery = true} for exactly that reason: nothing objected until
 * the server was running, and then every dispatch pass threw
 * {@code Illegal attempt to set lock mode for a native query} -- so the server
 * polled nothing at all while reporting itself healthy.
 *
 * <p>Reflection rather than a database, deliberately: this has to run
 * everywhere the unit tests run, including a machine with no Postgres.
 */
class RepositoryQueryContractTest {

    private static final String PACKAGE_PATH =
            "classpath*:com/nms/server/repository/**/*.class";

    @Test
    @DisplayName("no repository method combines @Lock with a native query")
    void lockModeIsNeverAppliedToANativeQuery() throws Exception {
        List<String> offenders = new ArrayList<>();

        for (Class<?> repository : repositoryInterfaces()) {
            for (Method method : repository.getDeclaredMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (query == null || !query.nativeQuery()) {
                    continue;
                }
                if (method.getAnnotation(Lock.class) != null) {
                    offenders.add(repository.getSimpleName() + "." + method.getName());
                }
            }
        }

        assertThat(offenders)
                .as("Hibernate rejects a lock mode on a native query at call time. "
                        + "Write the locking into the SQL (FOR UPDATE ... SKIP LOCKED) instead.")
                .isEmpty();
    }

    /**
     * The queries that claim work for several server instances must skip rows
     * their peers hold.
     *
     * <p>Without {@code SKIP LOCKED} a second instance blocks on the first
     * one's rows instead of taking different ones, which turns horizontal
     * scaling into a queue -- and it fails quietly, as throughput rather than
     * as an error.
     */
    @Test
    @DisplayName("claim queries lock with SKIP LOCKED")
    void claimQueriesSkipLockedRows() throws Exception {
        List<String> offenders = new ArrayList<>();

        for (Class<?> repository : repositoryInterfaces()) {
            for (Method method : repository.getDeclaredMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (query == null) {
                    continue;
                }
                String sql = query.value().toUpperCase();
                if (sql.contains("FOR UPDATE") && !sql.contains("SKIP LOCKED")) {
                    offenders.add(repository.getSimpleName() + "." + method.getName());
                }
            }
        }

        assertThat(offenders)
                .as("A FOR UPDATE without SKIP LOCKED serialises the instances "
                        + "that were meant to share this queue.")
                .isEmpty();
    }

    private List<Class<?>> repositoryInterfaces() throws Exception {
        MetadataReaderFactory metadata = new CachingMetadataReaderFactory();
        List<Class<?>> found = new ArrayList<>();

        for (Resource resource : new PathMatchingResourcePatternResolver()
                .getResources(PACKAGE_PATH)) {
            String name = metadata.getMetadataReader(resource).getClassMetadata().getClassName();
            Class<?> type = Class.forName(name);
            if (type.isInterface()) {
                found.add(type);
            }
        }

        // A guard on the guard. If the scan silently matched nothing -- a moved
        // package, a changed build layout -- both tests above would pass while
        // checking no code at all, which is worse than failing.
        assertThat(found)
                .as("repository interfaces found by classpath scan")
                .isNotEmpty();
        return found;
    }
}
