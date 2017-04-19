package at.spot.core.infrastructure.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import at.spot.core.infrastructure.exception.DeserializationException;
import at.spot.core.infrastructure.exception.SerializationException;
import at.spot.core.infrastructure.service.SerializationService;
import at.spot.core.infrastructure.strategy.SerializationStrategy;

@Service
public class DefaultSerializationService implements SerializationService {

	@Autowired
	SerializationStrategy jsonSerializationStrategy;

	SerializationStrategy xmlSerializationStrategy;

	/**
	 * Users @Gson to serialize any object to a json string.
	 */
	@Override
	public <T> String toJson(final T object) throws SerializationException {
		return jsonSerializationStrategy.serialize(object);
	}

	@Override
	public <T> String toXml(final T object) throws SerializationException {
		return xmlSerializationStrategy.serialize(object);
	}

	@Override
	public <T> T fromJson(final String value, final Class<T> type) throws DeserializationException {
		return jsonSerializationStrategy.deserialize(value, type);
	}

	@Override
	public <T> T fromXml(final String value, final Class<T> type) throws DeserializationException {
		return xmlSerializationStrategy.deserialize(value, type);
	}

}
