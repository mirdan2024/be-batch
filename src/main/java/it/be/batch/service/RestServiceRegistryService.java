package it.be.batch.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.be.batch.entity.BatchDefinition;
import it.be.batch.entity.BatchDefinition.HttpMethodType;
import it.be.batch.repo.BatchDefinitionRepository;
import it.common.base.bean.RestServiceMetadata;

@Service
public class RestServiceRegistryService {

	@Autowired
    private  BatchDefinitionRepository repository;

    @Transactional
    public void register(List<RestServiceMetadata> services) {

        if (services == null || services.isEmpty()) {
            return;
        }

        for (RestServiceMetadata metadata : services) {

            BatchDefinition entity = repository
                    .findByCode(metadata.getCode())
                    .orElseGet(BatchDefinition::new);

            entity.setCode(metadata.getCode());
            entity.setDescription(metadata.getDescription());
            entity.setEndpointUrl(metadata.getEndpoint());
            entity.setHttpMethod(HttpMethodType.valueOf(metadata.getHttpMethod()));
            entity.setBodyJson(metadata.getRequestJson());
            entity.setEnabled(true);
            entity.setDataCreazione(LocalDateTime.now());

            repository.save(entity);
        }
    }
}