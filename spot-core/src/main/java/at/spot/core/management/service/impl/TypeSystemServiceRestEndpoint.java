package at.spot.core.management.service.impl;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import at.spot.core.persistence.query.QueryCondition;
import at.spot.core.persistence.query.QueryResult;

import at.spot.core.infrastructure.exception.DeserializationException;
import at.spot.core.infrastructure.exception.ModelNotFoundException;
import at.spot.core.infrastructure.exception.ModelSaveException;
import at.spot.core.infrastructure.exception.ModelValidationException;
import at.spot.core.infrastructure.exception.UnknownTypeException;
import at.spot.core.infrastructure.http.HttpResponse;
import at.spot.core.infrastructure.http.Payload;
import at.spot.core.infrastructure.http.Status;
import at.spot.core.infrastructure.service.ModelService;
import at.spot.core.infrastructure.service.TypeService;
import at.spot.core.infrastructure.support.ItemTypeDefinition;
import at.spot.core.infrastructure.support.ItemTypePropertyDefinition;
import at.spot.core.infrastructure.support.MimeType;
import at.spot.core.management.annotation.Handler;
import at.spot.core.management.converter.Converter;
import at.spot.core.management.exception.RemoteServiceInitException;
import at.spot.core.management.support.data.GenericItemDefinitionData;
import at.spot.core.management.support.data.PageableData;
import at.spot.core.management.transformer.JsonResponseTransformer;
import at.spot.core.model.Item;
import at.spot.core.persistence.exception.ModelNotUniqueException;
import at.spot.core.persistence.exception.QueryException;
import at.spot.core.persistence.service.QueryService;
import at.spot.core.support.util.MiscUtil;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import spark.Request;
import spark.Response;
import spark.route.HttpMethod;

@Service
public class TypeSystemServiceRestEndpoint extends AbstractHttpServiceEndpoint {

	private static final String CONFIG_KEY_PORT = "service.typesystem.rest.port";
	private static final int DEFAULT_PORT = 19000;

	private static final int DEFAULT_PAGE = 1;
	private static final int DEFAULT_PAGE_SIZE = 100;

	@Autowired
	protected TypeService typeService;

	@Autowired
	protected ModelService modelService;

	@Autowired
	protected QueryService queryService;

	@Autowired
	protected Converter<ItemTypeDefinition, GenericItemDefinitionData> itemTypeConverter;

	@PostConstruct
	@Override
	public void init() throws RemoteServiceInitException {
		loggingService.info(String.format("Initiating remote type system REST service on port %s", getPort()));
		super.init();
	}

	/*
	 * TYPES
	 */

	@Handler(pathMapping = "/types/", mimeType = MimeType.JSON, responseTransformer = JsonResponseTransformer.class)
	public List<GenericItemDefinitionData> getTypes(final Request request, final Response response)
			throws UnknownTypeException {

		final List<GenericItemDefinitionData> types = new ArrayList<>();

		for (final String typeCode : typeService.getItemTypeDefinitions().keySet()) {
			final ItemTypeDefinition def = typeService.getItemTypeDefinition(typeCode);

			final GenericItemDefinitionData d = itemTypeConverter.convert(def);
			types.add(d);
		}

		return types;
	}

	@Handler(pathMapping = "/types/:typecode", mimeType = MimeType.JSON, responseTransformer = JsonResponseTransformer.class)
	public GenericItemDefinitionData getType(final Request request, final Response response)
			throws UnknownTypeException {
		GenericItemDefinitionData ret = null;

		final String typeCode = request.params(":typecode");

		if (StringUtils.isNotBlank(typeCode)) {
			final ItemTypeDefinition def = typeService.getItemTypeDefinitions().get(StringUtils.lowerCase(typeCode));

			if (def != null) {
				ret = itemTypeConverter.convert(def);
			} else {
				throw new UnknownTypeException(String.format("Type %s unknown.", typeCode));
			}
		}

		return ret;
	}

	/*
	 * MODELS
	 */

	/**
	 * Gets all items of the given item type. The page index starts at 1.
	 * 
	 * @param request
	 * @param response
	 * @return
	 * @throws UnknownTypeException
	 */
	@Handler(method = HttpMethod.get, pathMapping = "/v1/models/:typecode", mimeType = MimeType.JSON, responseTransformer = JsonResponseTransformer.class)
	public <T extends Item> HttpResponse<PageableData<T>> getModels(final Request request, final Response response)
			throws UnknownTypeException {

		final HttpResponse<PageableData<T>> body = new HttpResponse<>();

		List<T> models = null;

		final int page = MiscUtil.intOrDefault(request.queryParams("page"), DEFAULT_PAGE);
		final int pageSize = MiscUtil.intOrDefault(request.queryParams("pageSize"), DEFAULT_PAGE_SIZE);
		final Class<? extends Item> type = typeService.getType(request.params(":typecode"));

		models = (List<T>) modelService.getAll(type, null, page, pageSize);

		body.setBody(Payload.of(new PageableData<>(models, page, pageSize)));

		return body;
	}

	/**
	 * Gets an item based on the PK.
	 * 
	 * @param request
	 * @param response
	 * @return
	 * @throws ModelNotFoundException
	 * @throws UnknownTypeException
	 */
	@Handler(method = HttpMethod.get, pathMapping = "/v1/models/:typecode/:pk", mimeType = MimeType.JSON, responseTransformer = JsonResponseTransformer.class)
	public <T extends Item> HttpResponse<T> getModel(final Request request, final Response response)
			throws ModelNotFoundException, UnknownTypeException {

		final HttpResponse<T> body = new HttpResponse<>();

		final String typeCode = request.params(":typecode");
		final long pk = MiscUtil.longOrDefault(request.params(":pk"), -1);

		final Class<T> type = (Class<T>) typeService.getType(typeCode);
		final T model = modelService.get(type, pk);

		if (model == null) {
			body.setStatusCode(HttpStatus.NOT_FOUND);
		}

		body.setBody(Payload.of(model));

		return body;
	}

	/**
	 * Gets an item based on the search query. The query is a JEXL expression. <br/>
	 * 
	 * <br/>
	 * Example: .../User/query/uid='test-user' & name.contains('Vader') <br/>
	 * <br/>
	 * {@link QueryService#query(Class, QueryCondition, Comparator, int, int)} is
	 * called.
	 * 
	 * @param request
	 * @param response
	 * @return
	 * @throws UnknownTypeException
	 */
	public <T extends Item> Object queryModelByQuery(final Request request, final Response response)
			throws UnknownTypeException {

		final HttpResponse<QueryResult<T>> body = new HttpResponse<>();

		final int page = MiscUtil.intOrDefault(request.queryParams("page"), DEFAULT_PAGE);
		final int pageSize = MiscUtil.intOrDefault(request.queryParams("pageSize"), DEFAULT_PAGE_SIZE);
		final Class<T> type = (Class<T>) typeService.getType(request.params(":typecode"));

		final String[] queryStrings = request.queryParamsValues("query");

		if (queryStrings != null && queryStrings.length > 0) {
			final String queryString = MiscUtil.removeEnclosingQuotes(queryStrings[0]);

			try {
				final QueryResult<T> result = (QueryResult<T>) queryService.query(queryString, type, page, pageSize);

				body.setBody(Payload.of(result));
			} catch (final QueryException e) {
				body.setStatusCode(HttpStatus.BAD_REQUEST);
				body.getBody()
						.addError(new Status("error.query.execution", "Cannot execute given query: " + e.getMessage()));
			}
		} else {
			body.setStatusCode(HttpStatus.PRECONDITION_FAILED);
			body.getBody().addError(new Status("query.error", "Query could not be parsed."));
		}

		return body;
	}

	/**
	 * Gets an item based on the search query. <br/>
	 * <br/>
	 * Example: .../User/query/?uid=test-user&name=LordVader. <br/>
	 * <br/>
	 * {@link ModelService#get(Class, Map)} is called (=search by example).
	 * 
	 * @param request
	 * @param response
	 * @return
	 * @throws UnknownTypeException
	 */
	@SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
	public <T extends Item> Object queryModelByExample(final Request request, final Response response)
			throws UnknownTypeException {

		final HttpResponse<List<T>> body = new HttpResponse<>();

		final int page = MiscUtil.intOrDefault(request.queryParams("page"), DEFAULT_PAGE);
		final int pageSize = MiscUtil.intOrDefault(request.queryParams("pageSize"), DEFAULT_PAGE_SIZE);

		final String typeCode = request.params(":typecode");
		final Class<T> type = (Class<T>) typeService.getType(typeCode);

		final Map<String, String[]> query = request.queryMap().toMap();
		final Map<String, Object> searchParameters = new HashMap<>();

		for (final ItemTypePropertyDefinition prop : typeService.getItemTypeProperties(typeCode).values()) {
			final String[] queryValues = query.get(prop.name);

			if (queryValues != null && queryValues.length == 1) {
				final Class<?> propertyType = prop.returnType;

				Object value;
				try {
					value = serializationService.fromJson(queryValues[0], propertyType);
				} catch (final DeserializationException e) {
					body.setStatusCode(HttpStatus.PRECONDITION_FAILED);
					body.getBody().addError(new Status("error.query", e.getMessage()));
					return body;
				}

				searchParameters.put(prop.name, value);
			} else {
				body.getBody().addWarning(new Status("query.duplicateattribute",
						String.format("Query attribute %s passed more than once - only taking the first.", prop.name)));
			}
		}

		final List<T> models = modelService.getAll(type, searchParameters, page, pageSize);

		if (models == null) {
			body.setStatusCode(HttpStatus.NOT_FOUND);
		} else {
			body.setBody(Payload.of(models));
		}

		return body;
	}

	@Handler(method = HttpMethod.get, pathMapping = "/v1/models/:typecode/query/", mimeType = MimeType.JSON, responseTransformer = JsonResponseTransformer.class)
	public <T extends Item> Object queryModel(final Request request, final Response response)
			throws UnknownTypeException {

		final String[] queryParamValues = request.queryParamsValues("query");

		if (queryParamValues != null && queryParamValues.length > 0) {
			return queryModelByQuery(request, response);
		} else {
			return queryModelByExample(request, response);
		}
	}

	/**
	 * Creates a new item. If the item is not unique (based on its unique
	 * properties), an error is returned.
	 * 
	 * @param request
	 * @param response
	 * @return
	 * @throws UnknownTypeException
	 * @throws ModelSaveException
	 */
	@SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
	@Handler(method = HttpMethod.put, pathMapping = "/v1/models/:typecode", mimeType = MimeType.JSON, responseTransformer = JsonResponseTransformer.class)
	public <T extends Item> HttpResponse<Void> createModel(final Request request, final Response response)
			throws UnknownTypeException, ModelSaveException {

		final HttpResponse<Void> body = new HttpResponse<>(HttpStatus.PRECONDITION_FAILED);

		try {
			final T item = deserializeToItem(request);

			if (item.getPk() != null) {
				body.getBody()
						.addWarning(new Status("warning.general", "PK was reset, it may not be set for new items."));
			}

			modelService.save(item);
			body.setStatusCode(HttpStatus.CREATED);
		} catch (final DeserializationException e) {
			body.getBody().addError(new Status("error.oncreate", e.getMessage()));
		} catch (final ModelNotUniqueException e) {
			body.setStatusCode(HttpStatus.CONFLICT);
			body.getBody().addError(new Status("error.model.notunique",
					"Another item with the same uniqueness criteria (but a different PK) was found."));
		} catch (final ModelValidationException e) {
			final List<String> messages = e.getConstraintViolations().stream().map((c) -> {
				return String.format("%s.%s could not be set to {%s}: %s", c.getRootBean().getClass().getSimpleName(),
						c.getPropertyPath(), c.getInvalidValue(), c.getMessage());
			}).collect(Collectors.toList());

			body.setStatusCode(HttpStatus.CONFLICT);
			body.getBody().addError(new Status("error.model.validation", String.join("\n", messages)));
		}

		return body;
	}

	/**
	 * Removes the given item. The PK or a search criteria has to be set.
	 * 
	 * @param request
	 * @param response
	 * @return
	 * @throws UnknownTypeException
	 * @throws ModelSaveException
	 */
	@Handler(method = HttpMethod.delete, pathMapping = "/v1/models/:typecode/:pk", mimeType = MimeType.JSON, responseTransformer = JsonResponseTransformer.class)
	public <T extends Item> HttpResponse<Void> deleteModel(final Request request, final Response response)
			throws UnknownTypeException, ModelSaveException {

		final HttpResponse<Void> body = new HttpResponse<>();

		final String typeCode = request.params(":typecode");
		final long pk = MiscUtil.longOrDefault(request.params(":pk"), -1);

		if (pk > -1) {
			final Class<T> type = (Class<T>) typeService.getType(typeCode);
			try {
				modelService.remove(type, pk);
			} catch (final ModelNotFoundException e) {
				body.setStatusCode(HttpStatus.NOT_FOUND);
				body.getBody().addError(new Status("error.ondelete", "Item with given PK not found."));
			}
		} else {
			body.setStatusCode(HttpStatus.PRECONDITION_FAILED);
			body.getBody().addError(new Status("error.ondelete", "No valid PK given."));
		}

		body.setStatusCode(HttpStatus.ACCEPTED);

		return body;
	}

	/**
	 * Updates an item with the given values. The PK must be provided. If the new
	 * item is not unique, an error is returned.<br/>
	 * Attention: fields that are omitted will be treated as @null. If you just want
	 * to update a few fields, use the PATCH Method.
	 * 
	 * @param request
	 * @param response
	 * @return
	 * @throws UnknownTypeException
	 * @throws ModelSaveException
	 */
	@Handler(method = HttpMethod.post, pathMapping = "/v1/models/:typecode", mimeType = MimeType.JSON, responseTransformer = JsonResponseTransformer.class)
	public <T extends Item> HttpResponse<Void> updateModel(final Request request, final Response response)
			throws UnknownTypeException, ModelSaveException {

		final HttpResponse<Void> body = new HttpResponse<Void>();

		T item = null;

		try {
			item = deserializeToItem(request);
		} catch (final DeserializationException e) {
			body.setStatusCode(HttpStatus.PRECONDITION_FAILED);
			body.getBody().addError(new Status("error.onupdate", e.getMessage()));
			return body;
		}

		if (item.getPk() == null) {
			body.setStatusCode(HttpStatus.PRECONDITION_FAILED);
			body.getBody().addError(new Status("error.onupdate", "You cannot update a new item (PK was null)"));
		} else {
			try {
				modelService.save(item);
				body.setStatusCode(HttpStatus.ACCEPTED);
				item.markAsDirty();
			} catch (final ModelNotUniqueException | ModelValidationException e) {
				body.setStatusCode(HttpStatus.CONFLICT);
				body.getBody().addError(new Status("error.onupdate",
						"Another item with the same uniqueness criteria (but a different PK) was found."));
			}
		}

		return body;
	}

	@Handler(method = HttpMethod.patch, pathMapping = "/v1/models/:typecode/:pk", mimeType = MimeType.JSON, responseTransformer = JsonResponseTransformer.class)
	public <T extends Item> HttpResponse<Void> partiallyUpdateModel(final Request request, final Response response)
			throws UnknownTypeException, ModelSaveException {

		final HttpResponse<Void> body = new HttpResponse<>();

		// get type
		final String typeCode = request.params(":typecode");
		final Class<T> type = (Class<T>) typeService.getType(typeCode);

		final long pk = MiscUtil.longOrDefault(request.params(":pk"), -1);

		try {
			// get body as json object
			final JsonObject content = deserializeToJsonToken(request);

			// search old item
			final T oldItem = modelService.get(type, pk);

			if (oldItem == null) {
				throw new ModelNotFoundException(String.format("Item with PK=%s not  found", pk));
			}

			final Map<String, ItemTypePropertyDefinition> propertyDefinitions = typeService.getItemTypeProperties(type);

			for (final Entry<String, JsonElement> prop : content.entrySet()) {
				final String key = prop.getKey();
				final JsonElement value = prop.getValue();

				final ItemTypePropertyDefinition propDef = propertyDefinitions.get(key);

				// if the json property really exists on the item, then
				// continue
				if (propDef != null) {
					final Object parsedValue = serializationService.fromJson(value.toString(), propDef.returnType);
					modelService.setPropertyValue(oldItem, prop.getKey(), parsedValue);
				}
			}

			oldItem.markAsDirty();

			modelService.save(oldItem);

			body.setStatusCode(HttpStatus.ACCEPTED);
		} catch (final ModelNotUniqueException | ModelValidationException e) {
			body.setStatusCode(HttpStatus.CONFLICT);
			body.getBody().addError(new Status("error.onpartialupdate",
					"Another item with the same uniqueness criteria (but a different PK) was found."));
		} catch (final ModelNotFoundException e) {
			body.setStatusCode(HttpStatus.NOT_FOUND);
			body.getBody().addError(new Status("error.onpartialupdate", "No item with the given PK found to update."));
		} catch (final DeserializationException e) {
			body.setStatusCode(HttpStatus.PRECONDITION_FAILED);
			body.getBody().addError(new Status("error.onpartialupdate", "Could not deserialize body json content."));
		}

		return body;
	}

	protected <T extends Item> T deserializeToItem(final Request request)
			throws DeserializationException, UnknownTypeException {

		final String typeCode = request.params(":typecode");
		final Class<T> type = (Class<T>) typeService.getType(typeCode);

		final T item = serializationService.fromJson(request.body(), type);

		if (item == null) {
			throw new DeserializationException("Request body was empty");
		}

		return item;
	}

	protected JsonObject deserializeToJsonToken(final Request request)
			throws UnknownTypeException, DeserializationException {
		final String content = request.body();

		return serializationService.fromJson(content, JsonElement.class).getAsJsonObject();
	}

	/*
	 * 
	 */

	@Override
	public int getPort() {
		return configurationService.getInteger(CONFIG_KEY_PORT, DEFAULT_PORT);
	}

	@Override
	public InetAddress getBindAddress() {
		// not used
		// we listen everywhere
		return null;
	}
}
