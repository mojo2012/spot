package at.spot.core.infrastructure.service.impl;

import org.springframework.beans.factory.annotation.Autowired;

import at.spot.core.infrastructure.service.LoggingService;
import at.spot.core.infrastructure.service.ModelService;
import at.spot.core.infrastructure.service.TypeService;
import at.spot.core.model.Item;

public abstract class AbstractModelService extends AbstractService implements ModelService {

	@Autowired
	protected TypeService typeService;

	@Autowired
	protected LoggingService loggingService;

	@Override
	public <T extends Item> T create(Class<T> type) {
		return getApplicationContext().getBean(type);
	}

}
