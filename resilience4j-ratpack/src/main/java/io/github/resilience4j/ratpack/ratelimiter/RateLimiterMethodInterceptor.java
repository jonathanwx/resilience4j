/*
 * Copyright 2017 Dan Maas
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.resilience4j.ratpack.ratelimiter;

import com.google.inject.Inject;
import io.github.resilience4j.core.lang.Nullable;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.ratpack.internal.AbstractMethodInterceptor;
import io.github.resilience4j.ratpack.recovery.DefaultRecoveryFunction;
import io.github.resilience4j.ratpack.recovery.RecoveryFunction;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import ratpack.exec.Promise;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * A {@link MethodInterceptor} to handle all methods annotated with {@link RateLimiter}. It will
 * handle methods that return a {@link Promise}, {@link reactor.core.publisher.Flux}, {@link reactor.core.publisher.Mono}, {@link java.util.concurrent.CompletionStage}, or value.
 *
 * Given a method like this:
 * <pre><code>
 *     {@literal @}RateLimiter(name = "myService")
 *     public String fancyName(String name) {
 *         return "Sir Captain " + name;
 *     }
 * </code></pre>
 * each time the {@code #fancyName(String)} method is invoked, the method's execution will pass through a
 * a {@link io.github.resilience4j.ratelimiter.RateLimiter} according to the given config.
 *
 * The fallbackMethod signature must match either:
 *
 * 1) The method parameter signature on the annotated method or
 * 2) The method parameter signature with a matching exception type as the last parameter on the annotated method
 *
 * The return value can be a {@link Promise}, {@link java.util.concurrent.CompletionStage},
 * {@link reactor.core.publisher.Flux}, {@link reactor.core.publisher.Mono}, or an object value.
 * Other reactive types are not supported.
 *
 * If the return value is one of the reactive types listed above, it must match the return value type of the
 * annotated method.
 */
public class RateLimiterMethodInterceptor extends AbstractMethodInterceptor {

    @Inject(optional = true)
    @Nullable
    private RateLimiterRegistry registry;

    @SuppressWarnings("unchecked")
    @Nullable
    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        RateLimiter annotation = invocation.getMethod().getAnnotation(RateLimiter.class);
        final RecoveryFunction<?> fallbackMethod = Optional
                .ofNullable(createRecoveryFunction(invocation, annotation.fallbackMethod()))
                .orElse(new DefaultRecoveryFunction<>());
        if (registry == null) {
            registry = RateLimiterRegistry.ofDefaults();
        }
        io.github.resilience4j.ratelimiter.RateLimiter rateLimiter = registry.rateLimiter(annotation.name());
        Class<?> returnType = invocation.getMethod().getReturnType();
        if (Promise.class.isAssignableFrom(returnType)) {
            Promise<?> result = (Promise<?>) proceed(invocation, rateLimiter, fallbackMethod);
            if (result != null) {
                RateLimiterTransformer transformer = RateLimiterTransformer.of(rateLimiter).recover(fallbackMethod);
                result = result.transform(transformer);
            }
            return result;
        } else if (Flux.class.isAssignableFrom(returnType)) {
            Flux<?> result = (Flux<?>) proceed(invocation, rateLimiter, fallbackMethod);
            if (result != null) {
                RateLimiterOperator operator = RateLimiterOperator.of(rateLimiter);
                result = fallbackMethod.onErrorResume(result.transform(operator));
            }
            return result;
        } else if (Mono.class.isAssignableFrom(returnType)) {
            Mono<?> result = (Mono<?>) proceed(invocation, rateLimiter, fallbackMethod);
            if (result != null) {
                RateLimiterOperator operator = RateLimiterOperator.of(rateLimiter);
                result = fallbackMethod.onErrorResume(result.transform(operator));
            }
            return result;
        } else if (CompletionStage.class.isAssignableFrom(returnType)) {
            RateLimiterConfig rateLimiterConfig = rateLimiter.getRateLimiterConfig();
            Duration timeoutDuration = rateLimiterConfig.getTimeoutDuration();
            if (rateLimiter.acquirePermission(timeoutDuration)) {
                return proceed(invocation, rateLimiter, fallbackMethod);
            } else {
                final CompletableFuture promise = new CompletableFuture<>();
                Throwable t = new RequestNotPermitted(rateLimiter);
                completeFailedFuture(t, fallbackMethod, promise);
                return promise;
            }
        } else {
            return handleProceedWithException(invocation, rateLimiter, fallbackMethod);
        }
    }

    @Nullable
    private Object proceed(MethodInvocation invocation, io.github.resilience4j.ratelimiter.RateLimiter rateLimiter, RecoveryFunction<?> recoveryFunction) throws Throwable {
        Object result;
        try {
            result = invocation.proceed();
        } catch (Exception e) {
            result = handleProceedWithException(invocation, rateLimiter, recoveryFunction);
        }
        return result;
    }

    @Nullable
    private Object handleProceedWithException(MethodInvocation invocation, io.github.resilience4j.ratelimiter.RateLimiter rateLimiter, RecoveryFunction<?> recoveryFunction) throws Throwable {
        RateLimiterConfig rateLimiterConfig = rateLimiter.getRateLimiterConfig();
        Duration timeoutDuration = rateLimiterConfig.getTimeoutDuration();
        boolean permission = rateLimiter.acquirePermission(timeoutDuration);
        if (Thread.interrupted()) {
            throw new IllegalStateException("Thread was interrupted during permission wait");
        }
        if (!permission) {
            Throwable t = new RequestNotPermitted(rateLimiter);
            return recoveryFunction.apply(t);
        }
        return invocation.proceed();
    }

}
