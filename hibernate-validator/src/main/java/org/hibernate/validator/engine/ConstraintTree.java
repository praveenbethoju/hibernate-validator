/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, Red Hat, Inc. and/or its affiliates, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hibernate.validator.engine;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorFactory;
import javax.validation.ConstraintViolation;
import javax.validation.UnexpectedTypeException;
import javax.validation.ValidationException;
import javax.validation.metadata.ConstraintDescriptor;

import com.googlecode.jtype.TypeUtils;
import org.slf4j.Logger;

import org.hibernate.validator.constraints.CompositionType;
import org.hibernate.validator.metadata.ConstraintDescriptorImpl;
import org.hibernate.validator.util.LoggerFactory;
import org.hibernate.validator.util.ValidatorTypeHelper;

import static org.hibernate.validator.constraints.CompositionType.ALL_FALSE;
import static org.hibernate.validator.constraints.CompositionType.AND;
import static org.hibernate.validator.constraints.CompositionType.OR;

/**
 * Due to constraint composition a single constraint annotation can lead to a whole constraint tree being validated.
 * This class encapsulates such a tree.
 *
 * @author Hardy Ferentschik
 * @author Federico Mancini
 * @author Dag Hovland
 */
public class ConstraintTree<A extends Annotation> {

	private static final Logger log = LoggerFactory.make();

	private final ConstraintTree<?> parent;
	private final List<ConstraintTree<?>> children;
	private final ConstraintDescriptorImpl<A> descriptor;

	private final Map<Type, Class<? extends ConstraintValidator<?, ?>>> validatorTypes;

	private final Map<ValidatorCacheKey, ConstraintValidator<A, ?>> constraintValidatorCache;

	public ConstraintTree(ConstraintDescriptorImpl<A> descriptor) {
		this( descriptor, null );
	}

	private ConstraintTree(ConstraintDescriptorImpl<A> descriptor, ConstraintTree<?> parent) {
		this.parent = parent;
		this.descriptor = descriptor;
		this.constraintValidatorCache = new ConcurrentHashMap<ValidatorCacheKey, ConstraintValidator<A, ?>>();

		final Set<ConstraintDescriptorImpl<?>> composingConstraints = new HashSet<ConstraintDescriptorImpl<?>>();
		for ( ConstraintDescriptor<?> composingConstraint : descriptor.getComposingConstraints() ) {
			composingConstraints.add( (ConstraintDescriptorImpl<?>) composingConstraint );
		}

		children = new ArrayList<ConstraintTree<?>>( composingConstraints.size() );

		for ( ConstraintDescriptorImpl<?> composingDescriptor : composingConstraints ) {
			ConstraintTree<?> treeNode = createConstraintTree( composingDescriptor );
			children.add( treeNode );
		}

		validatorTypes = ValidatorTypeHelper.getValidatorsTypes( descriptor.getConstraintValidatorClasses() );
	}

	private <U extends Annotation> ConstraintTree<U> createConstraintTree(ConstraintDescriptorImpl<U> composingDescriptor) {
		return new ConstraintTree<U>( composingDescriptor, this );
	}

	public List<ConstraintTree<?>> getChildren() {
		return children;
	}

	public ConstraintDescriptorImpl<A> getDescriptor() {
		return descriptor;
	}

	public <T, U, V> boolean validateConstraints(Type type, ValidationContext<T> executionContext, ValueContext<U, V> valueContext) {
		List<ConstraintViolation<T>> constraintViolations = new ArrayList<ConstraintViolation<T>>();

		validateConstraints(
				type, executionContext, valueContext, constraintViolations
		);
		if ( !constraintViolations.isEmpty() ) {
			executionContext.addConstraintFailures( constraintViolations );
			return false;
		}
		return true;
	}

	private <T, U, V> void validateConstraints(Type type, ValidationContext<T> executionContext, ValueContext<U, V> valueContext, List<ConstraintViolation<T>> constraintViolations) {
		boolean allTrue = true;
		boolean atLeastOneTrue = false;

		// validate composing constraints (recursively)
		for ( ConstraintTree<?> tree : getChildren() ) {
			List<ConstraintViolation<T>> tmpViolations = new ArrayList<ConstraintViolation<T>>();
			tree.validateConstraints( type, executionContext, valueContext, tmpViolations );
			constraintViolations.addAll( tmpViolations );

			if ( tmpViolations.isEmpty() ) {
				atLeastOneTrue = true;
				// no need to further validate constraints, because at least one validation passed
				if ( descriptor.getCompositionType() == OR ) {
					break;
				}
			}
			else {
				allTrue = false;
			}
		}

		boolean passedValidation = passedCompositionTypeRequirement(
				constraintViolations, allTrue, atLeastOneTrue
		);

		// check whether we have constraints violations, but we should only report the single message of the
		// main constraint. We already have to generate the message here, since the composing constraints might
		// not have its own ConstraintValidator.
		// Also we want to leave it open to the final ConstraintValidator to generate a custom message. 
		if ( !passedValidation && reportAsSingleViolation() ) {

			constraintViolations.clear();
			final String message = (String) getDescriptor().getAttributes().get( "message" );
			MessageAndPath messageAndPath = new MessageAndPath( message, valueContext.getPropertyPath() );
			ConstraintViolation<T> violation = executionContext.createConstraintViolation(
					valueContext, messageAndPath, descriptor
			);
			constraintViolations.add( violation );
		}

		// after all children are validated the actual ConstraintValidator of the constraint itself is executed (provided
		// there is one
		if ( !descriptor.getConstraintValidatorClasses().isEmpty() ) {
			if ( log.isTraceEnabled() ) {
				log.trace(
						"Validating value {} against constraint defined by {}",
						valueContext.getCurrentValidatedValue(),
						descriptor
				);
			}
			ConstraintValidator<A, V> validator = getInitializedValidator(
					type,
					executionContext.getConstraintValidatorFactory()
			);

			ConstraintValidatorContextImpl constraintValidatorContext = new ConstraintValidatorContextImpl(
					valueContext.getPropertyPath(), descriptor
			);

			validateSingleConstraint(
					executionContext,
					valueContext,
					constraintViolations,
					constraintValidatorContext,
					validator
			);
		}
	}

	private <T> boolean passedCompositionTypeRequirement(List<ConstraintViolation<T>> constraintViolations, boolean allTrue, boolean atLeastOneTrue) {
		CompositionType compositionType = getDescriptor().getCompositionType();
		assert ( allTrue == constraintViolations.isEmpty() );
		boolean passedValidation = false;
		switch ( compositionType ) {
			case OR:
				passedValidation = atLeastOneTrue;
				break;
			case AND:
				passedValidation = allTrue;
				break;
			case ALL_FALSE:
				passedValidation = !atLeastOneTrue;
				break;
		}
		assert ( !passedValidation || !( compositionType == AND ) || constraintViolations.isEmpty() );
		if ( passedValidation ) {
			constraintViolations.clear();
		}
		return passedValidation;
	}

	private <T, U, V> void validateSingleConstraint(ValidationContext<T> executionContext, ValueContext<U, V> valueContext, List<ConstraintViolation<T>> constraintViolations, ConstraintValidatorContextImpl constraintValidatorContext, ConstraintValidator<A, V> validator) {
		boolean isValid;
		try {
			isValid = validator.isValid( valueContext.getCurrentValidatedValue(), constraintValidatorContext );
		}
		catch ( RuntimeException e ) {
			throw new ValidationException( "Unexpected exception during isValid call", e );
		}
		if ( !isValid ) {
			constraintViolations.addAll(
					executionContext.createConstraintViolations(
							valueContext, constraintValidatorContext
					)
			);
		}
	}

	/**
	 * @return {@code} true if the current constraint should be reportes as single violation, {@code false otherwise}.
	 * When using disjunction or negation, we only report the single top-level violation, as
	 * it is hard, especially for ALL_FALSE to give meaningful reports
	 */
	private boolean reportAsSingleViolation() {
		return getDescriptor().isReportAsSingleViolation()
				|| getDescriptor().getCompositionType() == ALL_FALSE;
	}

	/**
	 * @param type The type of the value to be validated (the type of the member/class the constraint was placed on).
	 * @param constraintFactory constraint factory used to instantiate the constraint validator.
	 *
	 * @return A initialized constraint validator matching the type of the value to be validated.
	 */
	@SuppressWarnings("unchecked")
	private <V> ConstraintValidator<A, V> getInitializedValidator(Type type, ConstraintValidatorFactory constraintFactory) {
		Class<? extends ConstraintValidator<?, ?>> validatorClass = findMatchingValidatorClass( type );

		// check if we have the default validator factory. If not we don't use caching (see HV-242)
		if ( !( constraintFactory instanceof ConstraintValidatorFactoryImpl ) ) {
			return createAndInitializeValidator( constraintFactory, validatorClass );
		}

		ConstraintValidator<A, V> constraintValidator;
		ValidatorCacheKey key = new ValidatorCacheKey( constraintFactory, validatorClass );
		if ( !constraintValidatorCache.containsKey( key ) ) {
			constraintValidator = createAndInitializeValidator( constraintFactory, validatorClass );
			constraintValidatorCache.put( key, constraintValidator );
		}
		else {
			if ( log.isTraceEnabled() ) {
				log.trace( "Constraint validator {} found in cache" );
			}
			constraintValidator = (ConstraintValidator<A, V>) constraintValidatorCache.get( key );
		}
		return constraintValidator;
	}

	@SuppressWarnings("unchecked")
	private <V> ConstraintValidator<A, V> createAndInitializeValidator(ConstraintValidatorFactory constraintFactory, Class<? extends ConstraintValidator<?, ?>> validatorClass) {
		ConstraintValidator<A, V> constraintValidator;
		constraintValidator = (ConstraintValidator<A, V>) constraintFactory.getInstance(
				validatorClass
		);
		if ( constraintValidator == null ) {
			throw new ValidationException(
					"Constraint factory returned null when trying to create instance of " + validatorClass.getName()
			);
		}
		initializeConstraint( descriptor, constraintValidator );
		return constraintValidator;
	}

	/**
	 * Runs the validator resolution algorithm.
	 *
	 * @param type The type of the value to be validated (the type of the member/class the constraint was placed on).
	 *
	 * @return The class of a matching validator.
	 */
	private Class<? extends ConstraintValidator<?, ?>> findMatchingValidatorClass(Type type) {
		List<Type> suitableTypes = findSuitableValidatorTypes( type );

		resolveAssignableTypes( suitableTypes );
		verifyResolveWasUnique( type, suitableTypes );

		return validatorTypes.get( suitableTypes.get( 0 ) );
	}

	private void verifyResolveWasUnique(Type valueClass, List<Type> assignableClasses) {
		if ( assignableClasses.size() == 0 ) {
			String className = valueClass.toString();
			if ( valueClass instanceof Class ) {
				Class<?> clazz = (Class<?>) valueClass;
				if ( clazz.isArray() ) {
					className = clazz.getComponentType().toString() + "[]";
				}
				else {
					className = clazz.getName();
				}
			}
			throw new UnexpectedTypeException( "No validator could be found for type: " + className );
		}
		else if ( assignableClasses.size() > 1 ) {
			StringBuilder builder = new StringBuilder();
			builder.append( "There are multiple validator classes which could validate the type " );
			builder.append( valueClass );
			builder.append( ". The validator classes are: " );
			for ( Type clazz : assignableClasses ) {
				builder.append( clazz );
				builder.append( ", " );
			}
			builder.delete( builder.length() - 2, builder.length() );
			throw new UnexpectedTypeException( builder.toString() );
		}
	}

	private List<Type> findSuitableValidatorTypes(Type type) {
		List<Type> suitableTypes = new ArrayList<Type>();
		for ( Type validatorType : validatorTypes.keySet() ) {
			if ( TypeUtils.isAssignable( validatorType, type ) && !suitableTypes.contains( validatorType ) ) {
				suitableTypes.add( validatorType );
			}
		}
		return suitableTypes;
	}

	/**
	 * Tries to reduce all assignable classes down to a single class.
	 *
	 * @param assignableTypes The set of all classes which are assignable to the class of the value to be validated and
	 * which are handled by at least one of the  validators for the specified constraint.
	 */
	private void resolveAssignableTypes(List<Type> assignableTypes) {
		if ( assignableTypes.size() == 0 || assignableTypes.size() == 1 ) {
			return;
		}

		List<Type> typesToRemove = new ArrayList<Type>();
		do {
			typesToRemove.clear();
			Type type = assignableTypes.get( 0 );
			for ( int i = 1; i < assignableTypes.size(); i++ ) {
				if ( TypeUtils.isAssignable( type, assignableTypes.get( i ) ) ) {
					typesToRemove.add( type );
				}
				else if ( TypeUtils.isAssignable( assignableTypes.get( i ), type ) ) {
					typesToRemove.add( assignableTypes.get( i ) );
				}
			}
			assignableTypes.removeAll( typesToRemove );
		} while ( typesToRemove.size() > 0 );
	}

	private <V> void initializeConstraint
			(ConstraintDescriptor<A>
					descriptor, ConstraintValidator<A, V>
					constraintValidator) {
		try {
			constraintValidator.initialize( descriptor.getAnnotation() );
		}
		catch ( RuntimeException e ) {
			throw new ValidationException( "Unable to initialize " + constraintValidator.getClass().getName(), e );
		}
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder();
		sb.append( "ConstraintTree" );
		sb.append( "{ descriptor=" ).append( descriptor );
		sb.append( ", isRoot=" ).append( parent == null );
		sb.append( ", constraintValidatorCache=" ).append( constraintValidatorCache );
		sb.append( '}' );
		return sb.toString();
	}

	private static class ValidatorCacheKey {
		private ConstraintValidatorFactory constraintValidatorFactory;
		private Class<? extends ConstraintValidator<?, ?>> validatorType;

		private ValidatorCacheKey(ConstraintValidatorFactory constraintValidatorFactory, Class<? extends ConstraintValidator<?, ?>> validatorType) {
			this.constraintValidatorFactory = constraintValidatorFactory;
			this.validatorType = validatorType;
		}

		@Override
		public boolean equals(Object o) {
			if ( this == o ) {
				return true;
			}
			if ( o == null || getClass() != o.getClass() ) {
				return false;
			}

			ValidatorCacheKey that = (ValidatorCacheKey) o;

			if ( constraintValidatorFactory != null ? !constraintValidatorFactory.equals( that.constraintValidatorFactory ) : that.constraintValidatorFactory != null ) {
				return false;
			}
			if ( validatorType != null ? !validatorType.equals( that.validatorType ) : that.validatorType != null ) {
				return false;
			}

			return true;
		}

		@Override
		public int hashCode() {
			int result = constraintValidatorFactory != null ? constraintValidatorFactory.hashCode() : 0;
			result = 31 * result + ( validatorType != null ? validatorType.hashCode() : 0 );
			return result;
		}
	}
}
