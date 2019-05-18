/*
 * Copyright 2019 Kyuhyen Hwang
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
package io.github.resilience4j.recovery;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import io.github.resilience4j.core.lang.Nullable;

/**
 * Reflection utility for invoking fallbackMethod method. Recovery method should have same return type and parameter types of original method but the last additional parameter.
 * The last additional parameter should be a subclass of {@link Throwable}. When {@link FallbackMethod#fallback(Throwable)} is invoked, {@link Throwable} will be passed to that last parameter.
 * If there are multiple fallbackMethod method, one of the methods that has most closest superclass parameter of thrown object will be invoked.
 * <pre>
 * For example, there are two fallbackMethod methods
 * {@code
 * String fallbackMethod(String parameter, RuntimeException exception)
 * String fallbackMethod(String parameter, IllegalArgumentException exception)
 * }
 * and if try to fallback from {@link NumberFormatException}, {@code String fallbackMethod(String parameter, IllegalArgumentException exception)} will be invoked.
 * </pre>
 */
public class FallbackMethod {
	private static final Map<MethodMeta, Map<Class<?>, Method>> RECOVERY_METHODS_CACHE = new ConcurrentReferenceHashMap<>();
	private final Map<Class<?>, Method> recoveryMethods;
	private final Object[] args;
	private final Object target;
	private final Class<?> returnType;

	/**
	 * create a fallbackMethod method.
	 *
	 * @param recoveryMethods          configured and found recovery methods for this invocation
	 * @param originalMethodReturnType the return type of the original source method
	 * @param args                     arguments those were passed to the original method. They will be passed to the fallbackMethod method.
	 * @param target                   target object the fallbackMethod method will be invoked
	 */
	private FallbackMethod(Map<Class<?>, Method> recoveryMethods, Class<?> originalMethodReturnType, Object[] args, Object target) {

		this.recoveryMethods = recoveryMethods;
		this.args = args;
		this.target = target;
		this.returnType = originalMethodReturnType;
	}

	/**
	 * @return FallbackMethod builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * try to fallback from {@link Throwable}
	 *
	 * @param thrown {@link Throwable} that should be fallback
	 * @return recovered value
	 * @throws Throwable if throwable is unrecoverable, throwable will be thrown
	 */
	@Nullable
	public Object fallback(Throwable thrown) throws Throwable {
		if (recoveryMethods.size() == 1) {
			Map.Entry<Class<?>, Method> entry = recoveryMethods.entrySet().iterator().next();
			if (entry.getKey().isAssignableFrom(thrown.getClass())) {
				return invoke(entry.getValue(), thrown);
			} else {
				throw thrown;
			}
		}

		Method recovery = null;

		for (Class<?> thrownClass = thrown.getClass(); recovery == null && thrownClass != Object.class; thrownClass = thrownClass.getSuperclass()) {
			recovery = recoveryMethods.get(thrownClass);
		}

		if (recovery == null) {
			throw thrown;
		}

		return invoke(recovery, thrown);
	}

	/**
	 * get return type of fallbackMethod method
	 *
	 * @return return type of fallbackMethod method
	 */
	public Class<?> getReturnType() {
		return returnType;
	}

	/**
	 * invoke the fallback method logic
	 *
	 * @param fallback  fallback method
	 * @param throwable the thrown exception
	 * @return the result object if any
	 * @throws IllegalAccessException
	 * @throws InvocationTargetException
	 */
	private Object invoke(Method fallback, Throwable throwable) throws IllegalAccessException, InvocationTargetException {
		boolean accessible = fallback.isAccessible();
		try {
			if (!accessible) {
				ReflectionUtils.makeAccessible(fallback);
			}
			if (args.length != 0) {
				if (args.length == 1 && Throwable.class.isAssignableFrom(fallback.getParameterTypes()[0])) {
					return fallback.invoke(target, throwable);
				}
				Object[] newArgs = Arrays.copyOf(args, args.length + 1);
				newArgs[args.length] = throwable;

				return fallback.invoke(target, newArgs);

			} else {
				return fallback.invoke(target, throwable);
			}
		} finally {
			if (!accessible) {
				fallback.setAccessible(false);
			}
		}
	}

	/**
	 * the FallbackMethod builder class
	 */
	public static class Builder {
		private String recoveryMethodName;
		private Method originalMethod;
		private Object[] args;
		private Object target;

		/**
		 * @param recoveryMethodName the configured recovery method name
		 * @return Builder of FallbackMethod
		 */
		public Builder recoveryMethodName(String recoveryMethodName) {
			this.recoveryMethodName = recoveryMethodName;
			return this;
		}

		/**
		 * @param originalMethod the original method which has fallback method configured
		 * @return Builder of FallbackMethod
		 */
		public Builder originalMethod(Method originalMethod) {
			this.originalMethod = originalMethod;
			return this;
		}

		/**
		 * @param args the original method arguments
		 * @return Builder of FallbackMethod
		 */
		public Builder originalMethodArgs(Object[] args) {
			this.args = args;
			return this;
		}

		/**
		 * @param target the target class that own the original method and recovery method
		 * @return Builder of FallbackMethod
		 */
		public Builder targetObject(Object target) {
			this.target = target;
			return this;
		}

		/**
		 * @return FallbackMethod if any
		 * @throws NoSuchMethodException will be thrown, if fallbackMethod method is not found
		 */
		public FallbackMethod build() throws NoSuchMethodException {
			Class<?>[] params = originalMethod.getParameterTypes();
			Class<?> originalReturnType = originalMethod.getReturnType();

			Map<Class<?>, Method> methods = extractMethods(recoveryMethodName, params, originalReturnType, target.getClass());

			if (methods.isEmpty()) {
				throw new NoSuchMethodException(String.format("%s %s.%s(%s,%s)", originalReturnType, target.getClass(), recoveryMethodName, StringUtils.arrayToDelimitedString(params, ","), Throwable.class));
			}
			return new FallbackMethod(methods, originalReturnType, args, target);
		}
	}

	/**
	 * @param fallbackMethodName fallback method name
	 * @param params             original method parameters
	 * @param originalReturnType original method return type
	 * @param targetClass        the owner class
	 * @return Map<Class < ?>, Method>  map of all configure fallback methods for the original method that match the fallback method name
	 */
	private static Map<Class<?>, Method> extractMethods(String fallbackMethodName, Class<?>[] params, Class<?> originalReturnType, Class<?> targetClass) {
		MethodMeta methodMeta = new MethodMeta(fallbackMethodName, params, originalReturnType, targetClass);
		Map<Class<?>, Method> cachedMethods = RECOVERY_METHODS_CACHE.get(methodMeta);

		if (cachedMethods != null) {
			return cachedMethods;
		}

		Map<Class<?>, Method> methods = new HashMap<>();

		ReflectionUtils.doWithMethods(targetClass, method -> {
			Class<?>[] recoveryParams = method.getParameterTypes();
			if (methods.get(recoveryParams[recoveryParams.length - 1]) != null) {
				throw new IllegalStateException("You have more that one fallback method that cover the same exception type " + recoveryParams[recoveryParams.length - 1].getSimpleName());
			}
			methods.put(recoveryParams[recoveryParams.length - 1], method);
		}, method -> {
			if (method.getParameterCount() == 1) {
				if (!method.getName().equals(fallbackMethodName) || !originalReturnType.isAssignableFrom(method.getReturnType())) {
					return false;
				}
				return Throwable.class.isAssignableFrom(method.getParameterTypes()[0]);
			} else {
				if (!method.getName().equals(fallbackMethodName) || method.getParameterCount() != params.length + 1) {
					return false;
				}
				if (!originalReturnType.isAssignableFrom(method.getReturnType())) {
					return false;
				}

				Class[] targetParams = method.getParameterTypes();
				for (int i = 0; i < params.length; i++) {
					if (params[i] != targetParams[i]) {
						return false;
					}
				}
				return Throwable.class.isAssignableFrom(targetParams[params.length]);
			}

		});

		RECOVERY_METHODS_CACHE.putIfAbsent(methodMeta, methods);
		return methods;
	}

	/**
	 * fallback method cache lookup key
	 */
	private static class MethodMeta {
		final String recoveryMethodName;
		final Class<?>[] params;
		final Class<?> returnType;
		final Class<?> targetClass;

		MethodMeta(String recoveryMethodName, Class<?>[] params, Class<?> returnType, Class<?> targetClass) {
			this.recoveryMethodName = recoveryMethodName;
			this.params = params;
			this.returnType = returnType;
			this.targetClass = targetClass;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			MethodMeta that = (MethodMeta) o;
			return targetClass.equals(that.targetClass) &&
					recoveryMethodName.equals(that.recoveryMethodName) &&
					returnType.equals(that.returnType) &&
					Arrays.equals(params, that.params);
		}

		@Override
		public int hashCode() {
			return targetClass.getName().hashCode() ^ recoveryMethodName.hashCode();
		}
	}
}