/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.jpa.repository.support;

import static org.springframework.data.jpa.repository.query.QueryUtils.*;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.persistence.EntityManager;
import javax.persistence.LockModeType;
import javax.persistence.NoResultException;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.ParameterExpression;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.query.Jpa21Utils;
import org.springframework.data.jpa.repository.query.JpaEntityGraph;
import org.springframework.data.repository.augment.QueryAugmentationEngine;
import org.springframework.data.repository.augment.QueryContext.QueryMode;
import org.springframework.util.Assert;

/**
 * @author Oliver Gierke
 */
public class QueryExecutor<T, ID extends Serializable> {

	private final EntityManager em;
	private final JpaEntityInformation<T, ID> entityInformation;
	private final QueryAugmentationEngine engine;
	private final CrudMethodMetadata metadata;

	/**
	 * @param entityInformation
	 */
	public QueryExecutor(JpaEntityInformation<T, ID> entityInformation, EntityManager em, QueryAugmentationEngine engine,
			CrudMethodMetadata metadata) {

		this.entityInformation = entityInformation;
		this.em = em;
		this.engine = engine;
		this.metadata = metadata;
	}

	/**
	 * Creates a {@link TypedQuery} for the given {@link Specification} and {@link Sort}.
	 * 
	 * @param spec can be {@literal null}.
	 * @param sort can be {@literal null}.
	 * @return
	 */
	public TypedQuery<T> getQuery(Specification<T> spec, Sort sort) {

		CriteriaBuilder builder = em.getCriteriaBuilder();
		CriteriaQuery<T> query = builder.createQuery(entityInformation.getJavaType());

		Root<T> root = applySpecificationToCriteria(spec, query);

		JpaCriteriaQueryContext<T, T> context = potentiallyAugment(query, root, QueryMode.FIND);
		query = context.getQuery();
		root = context.getRoot();

		query.select(root);

		if (sort != null) {
			query.orderBy(toOrders(sort, root, builder));
		}

		return applyRepositoryMethodMetadata(em.createQuery(query));
	}

	public T executeFindOneFor(ID id) {

		ByIdSpecification<T, ID> spec = new ByIdSpecification<T, ID>(entityInformation);
		TypedQuery<T> typedQuery = getQuery(spec, (Sort) null);

		try {

			typedQuery.setParameter(spec.parameter, id);
			return typedQuery.getSingleResult();

		} catch (NoResultException e) {
			return null;
		}
	}

	public Long executeCountByIdFor(ID id, QueryMode mode) {

		ByIdSpecification<T, ID> specification = new ByIdSpecification<T, ID>(entityInformation);

		TypedQuery<Long> query = getCountQuery(specification, mode);
		query.setParameter(specification.parameter, id);

		return executeCountQuery(query);
	}

	public Long executeCountByIdFor(T entity, QueryMode mode) {
		return executeCountByIdFor(entityInformation.getId(entity), mode);
	}

	public Long executeCountQueryFor(Specification<T> spec, QueryMode mode) {
		return executeCountQuery(getCountQuery(spec, mode));
	}

	protected TypedQuery<Long> getCountQuery(Specification<T> spec, QueryMode mode) {

		CriteriaBuilder builder = em.getCriteriaBuilder();
		CriteriaQuery<Long> query = builder.createQuery(Long.class);

		Root<T> root = applySpecificationToCriteria(spec, query);

		JpaCriteriaQueryContext<Long, T> context = potentiallyAugment(query, root, mode);
		query = context.getQuery();
		root = context.getRoot();

		if (query.isDistinct()) {
			query.select(builder.countDistinct(root));
		} else {
			query.select(builder.count(root));
		}

		return applyRepositoryMethodMetadata(em.createQuery(query));
	}

	/**
	 * Applies the given {@link Specification} to the given {@link CriteriaQuery}.
	 * 
	 * @param spec can be {@literal null}.
	 * @param query must not be {@literal null}.
	 * @return
	 */
	private <S> Root<T> applySpecificationToCriteria(Specification<T> spec, CriteriaQuery<S> query) {

		Assert.notNull(query);
		Root<T> root = query.from(entityInformation.getJavaType());

		if (spec == null) {
			return root;
		}

		CriteriaBuilder builder = em.getCriteriaBuilder();
		Predicate predicate = spec.toPredicate(root, query, builder);

		if (predicate != null) {
			query.where(predicate);
		}

		return root;
	}

	/**
	 * Executes a count query and transparently sums up all values returned.
	 * 
	 * @param query must not be {@literal null}.
	 * @return
	 */
	private Long executeCountQuery(TypedQuery<Long> query) {

		Assert.notNull(query);

		List<Long> totals = query.getResultList();
		Long total = 0L;

		for (Long element : totals) {
			total += element == null ? 0 : element;
		}

		return total;
	}

	private <Q> TypedQuery<Q> applyRepositoryMethodMetadata(TypedQuery<Q> query) {

		if (metadata == null) {
			return query;
		}

		LockModeType type = metadata.getLockModeType();
		TypedQuery<Q> toReturn = type == null ? query : query.setLockMode(type);

		return applyQueryHints(toReturn);
	}

	private <Q extends Query> Q applyQueryHints(Q query) {

		for (Entry<String, Object> hint : getQueryHints().entrySet()) {
			query.setHint(hint.getKey(), hint.getValue());
		}

		return query;
	}

	/**
	 * Returns a {@link Map} with the query hints based on the current {@link CrudMethodMetadata} and potential
	 * {@link EntityGraph} information.
	 * 
	 * @return
	 */
	protected Map<String, Object> getQueryHints() {

		if (metadata.getEntityGraph() == null) {
			return metadata.getQueryHints();
		}

		Map<String, Object> hints = new HashMap<String, Object>();
		hints.putAll(metadata.getQueryHints());
		hints.putAll(Jpa21Utils.tryGetFetchGraphHints(em, getEntityGraph(), entityInformation.getJavaType()));

		return hints;
	}

	private JpaEntityGraph getEntityGraph() {

		String fallbackName = this.entityInformation.getEntityName() + "." + metadata.getMethod().getName();
		return new JpaEntityGraph(metadata.getEntityGraph(), fallbackName);
	}

	private <S> JpaCriteriaQueryContext<S, T> potentiallyAugment(CriteriaQuery<S> query, Root<T> root, QueryMode mode) {

		JpaCriteriaQueryContext<S, T> context = new JpaCriteriaQueryContext<S, T>(mode, em, query, entityInformation, root);

		if (engine.augmentationNeeded(JpaCriteriaQueryContext.class, mode, entityInformation)) {
			context = engine.invokeAugmentors(context);
		}

		return context;
	}

	private static class ByIdSpecification<T, ID extends Serializable> implements Specification<T> {

		private final JpaEntityInformation<T, ID> entityInformation;
		private ParameterExpression<ID> parameter;

		public ByIdSpecification(JpaEntityInformation<T, ID> entityInformation) {
			this.entityInformation = entityInformation;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.jpa.domain.Specification#toPredicate(javax.persistence.criteria.Root, javax.persistence.criteria.CriteriaQuery, javax.persistence.criteria.CriteriaBuilder)
		 */
		public Predicate toPredicate(Root<T> root, CriteriaQuery<?> query, CriteriaBuilder cb) {

			Path<?> path = root.get(entityInformation.getIdAttribute());
			parameter = cb.parameter(entityInformation.getIdType());
			return cb.equal(path, parameter);
		}
	}
}
