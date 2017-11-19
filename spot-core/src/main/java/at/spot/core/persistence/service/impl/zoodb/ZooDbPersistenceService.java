package at.spot.core.persistence.service.impl.zoodb;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.jdo.PersistenceManager;
import javax.jdo.Query;

import org.apache.commons.lang3.StringUtils;
import org.zoodb.jdo.ZooJdoHelper;

import at.spot.core.infrastructure.annotation.Property;
import at.spot.core.infrastructure.exception.ModelNotFoundException;
import at.spot.core.infrastructure.exception.ModelSaveException;
import at.spot.core.model.Item;
import at.spot.core.persistence.exception.CannotCreateModelProxyException;
import at.spot.core.persistence.exception.ModelNotUniqueException;
import at.spot.core.persistence.service.PersistenceService;
import at.spot.core.persistence.service.impl.AbstractPersistenceService;
import at.spot.core.support.util.ClassUtil;

public class ZooDbPersistenceService extends AbstractPersistenceService implements PersistenceService {

	protected static final String DB_FILE = "/var/tmp/spot.zdb";

	protected PersistenceManager pm;

	public ZooDbPersistenceService() {
		this.pm = ZooJdoHelper.openOrCreateDB(DB_FILE);
	}

	@PostConstruct
	public void setup() {
		pm.currentTransaction().setNontransactionalRead(true);
	}

	@PreDestroy
	public void shutdown() {
		pm.close();
		pm.getPersistenceManagerFactory().close();
	}

	@Override
	public <T extends Item> void save(final T... models) throws ModelSaveException, ModelNotUniqueException {
		save(Arrays.asList(models));
	}

	@Override
	public <T extends Item> void save(final List<T> models) throws ModelSaveException, ModelNotUniqueException {
		try {
			beginTransaction();

			for (final T item : models) {
				pm.makePersistent(item);
			}

			commit();
		} catch (final Exception e) {
			rollback();
			throw new ModelSaveException("Could not save given items", e);
		}
	}

	@Override
	public <T extends Item> T load(final Class<T> type, final long pk) throws ModelNotFoundException {
		T item = null;

		try {
			beginTransaction();

			item = (T) pm.getObjectById(pk);

			commit();
		} catch (final Exception e) {
			rollback();
			throw new ModelNotFoundException(type, pk);
		}

		return item;
	}

	@Override
	public <T extends Item> void refresh(final T item) throws ModelNotFoundException {
		try {
			beginTransaction();
			pm.refresh(item);
		} catch (final Exception e) {
			rollback();
			throw new ModelNotFoundException(String.format("Could not refresh item with pk=%s.", item.getPk()), e);
		} finally {
			commit();
		}
	}

	@Override
	public <T extends Item> List<T> load(final Class<T> type, final Map<String, Object> searchParameters) {
		return load(type, searchParameters, null, null);
	}

	@Override
	public <T extends Item> List<T> load(final Class<T> type, final Map<String, Object> searchParameters,
			final Integer page, final Integer pageSize) {

		if (searchParameters != null && !searchParameters.isEmpty()) {
			final List<String> criteria = buildQueryStringWithNonItemTypeProperties(searchParameters, null);
			final String criteriaString = StringUtils.join(criteria, " AND ");

			try {
				beginTransaction();
				final Query query = pm.newQuery(type, criteriaString);
				return new ArrayList<T>((Collection<T>) query.execute());
			} catch (final Exception e) {
				loggingService.warn(String.format("Could not load item of type %s with search params %s.",
						type.getName(), searchParameters));
			} finally {
				commit();
			}
		}

		return Collections.emptyList();
	}

	protected <T extends Item> List<String> buildQueryStringWithNonItemTypeProperties(final Map<String, Object> params,
			final String propertyName) {

		final List<String> criteria = new ArrayList<>();

		final String prefix = propertyName != null ? propertyName + "." : "";

		for (final Map.Entry<String, Object> entry : params.entrySet()) {
			if (!(entry.getValue() instanceof Item)) {
				criteria.add(String.format("%s%s == '%s'", prefix, entry.getKey(), entry.getValue()));
			} else {
				final Map<String, Object> subItem = convertItemToMap((Item) entry.getValue());
				criteria.addAll(buildQueryStringWithNonItemTypeProperties(subItem, entry.getKey()));
			}
		}

		return criteria;
	}

	@Override
	public <T extends Item> void loadProxyModel(final T proxyItem) throws ModelNotFoundException {
		refresh(proxyItem);
	}

	@Override
	public <T extends Item> T createProxyModel(final T item) throws CannotCreateModelProxyException {
		return item;
	}

	@Override
	public <T extends Item> void remove(final T... items) {
		beginTransaction();

		for (final T item : items) {
			try {
				pm.deletePersistent(Arrays.asList(items));
			} catch (final Exception e) {
				rollback();
			}
		}

		commit();
	}

	@Override
	public <T extends Item> void remove(final Class<T> type, final long pk) {
		T item;
		try {
			beginTransaction();
			item = load(type, pk);
			pm.deletePersistent(item);
		} catch (final ModelNotFoundException e) {
			loggingService
					.debug(String.format("Item of type %s with pk %s not found for deletion.", type.getName(), pk));
			rollback();
		}
	}

	@Override
	public void saveDataStorage() {
		try {
			beginTransaction();
			pm.flush();
			commit();
		} catch (final Exception e) {
			rollback();
		}
	}

	@Override
	public void clearDataStorage() {
	}

	@Override
	public <T extends Item> void initItem(final T item) {
		for (final Field field : ClassUtil.getFieldsWithAnnotation(item.getClass(), Property.class)) {
			if (field.getType().isAssignableFrom(List.class)) {
				ClassUtil.setField(item, field.getName(), new ArrayList());
			} else if (field.getType().isAssignableFrom(Set.class)) {
				ClassUtil.setField(item, field.getName(), new HashSet());
			} else if (field.getType().isAssignableFrom(Map.class)) {
				ClassUtil.setField(item, field.getName(), new HashMap());
			}
		}
	}

	@Override
	public <T extends Item> void detach(final T... items) {
		try {
			beginTransaction();
			pm.detachCopyAll(items);
			commit();
		} catch (final Exception e) {
			rollback();
		}
	}

	protected void beginTransaction() {
		if (this.pm.currentTransaction() == null || !pm.currentTransaction().isActive()) {
			this.pm.currentTransaction().begin();
		}
	}

	protected void commit() {
		this.pm.currentTransaction().commit();
	}

	protected void rollback() {
		if (pm.currentTransaction() != null && pm.currentTransaction().isActive()) {
			this.pm.currentTransaction().rollback();
		}
	}

}
