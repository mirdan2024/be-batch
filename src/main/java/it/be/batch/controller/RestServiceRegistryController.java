package it.be.batch.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import it.be.batch.service.RestServiceRegistryService;
import it.common.base.bean.RestServiceMetadata;

@RestController
@RequestMapping
public class RestServiceRegistryController {

	@Autowired
    private  RestServiceRegistryService service;

    @PostMapping("/register")
    public void register(
            @RequestBody List<RestServiceMetadata> services
    ) {
        service.register(services);
    }
}