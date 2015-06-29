/*
 * Copyright 2013-2015 the original author or authors.
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

import javax.persistence.EntityManager;
import javax.persistence.Query;

import org.springframework.data.jpa.provider.PersistenceProvider;
import org.springframework.data.jpa.provider.QueryExtractor;
import org.springframework.data.jpa.repository.query.QueryUtils;
import org.springframework.data.repository.augment.QueryContext;

/**
 * @author Oliver Gierke
 */
public class JpaQueryContext extends QueryContext<Query> {

	private final EntityManager entityManager;
	private final QueryExtractor extractor;

	/**
	 * @param query must not be {@literal null}.
	 * @param queryMode must not be {@literal null}.
	 * @param entityManager must not be {@literal null}.
	 */
	public JpaQueryContext(Query query, QueryMode queryMode, EntityManager entityManager) {

		super(query, queryMode);
		this.entityManager = entityManager;
		this.extractor = PersistenceProvider.fromEntityManager(entityManager);
	}

	/**
	 * @return the entityManager
	 */
	public EntityManager getEntityManager() {
		return entityManager;
	}

	public String getQueryString() {
		return extractor.extractQueryString(getQuery());
	}

	/**
	 * Creates a new {@link JpaQueryContext} from the current one augmenting the query with the given {@code from} and
	 * {@code where} clause. The where clause can use a placeholder of <code>{alias}</code> to be replaced with the main
	 * alias of the original query.
	 * 
	 * @param from must not be {@literal null}.
	 * @param where must not be {@literal null}.
	 * @return
	 */
	public JpaQueryContext augment(String from, String where) {

		String queryString = getQueryString();
		Query createQuery = entityManager.createQuery(
				QueryUtils.addFromAndWhere(queryString, from, where.replace("{alias}", QueryUtils.detectAlias(queryString))));

		return new JpaQueryContext(createQuery, getMode(), entityManager);
	}
}
