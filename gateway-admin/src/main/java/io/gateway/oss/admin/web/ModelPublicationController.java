package io.gateway.oss.admin.web;

import io.gateway.oss.core.security.ClientAuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/admin")
public class ModelPublicationController extends AdminBaseController {

    private final ModelPublicationService modelPublicationService;

    public ModelPublicationController(ClientAuthService clientAuthService,
                                      ModelPublicationService modelPublicationService) {
        super(clientAuthService);
        this.modelPublicationService = modelPublicationService;
    }

    @PutMapping("/publications/{alias}")
    public Mono<ResponseEntity<ModelPublicationService.PublicationResponse>> publish(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String alias,
            @RequestBody ModelPublicationService.PublishRequest request) {
        requireAdminAccess(authorizationHeader);
        return modelPublicationService.publish(alias, request)
                .map(outcome -> ResponseEntity.status(outcome.created() ? 201 : 200).body(outcome.response()));
    }
}
