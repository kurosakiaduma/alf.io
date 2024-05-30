/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
/**
 * This file is part of alf.io.
 * <p>
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.controller.api.admin;

import alfio.controller.support.TemplateProcessor;
import alfio.manager.FileUploadManager;
import alfio.manager.UploadedResourceManager;
import alfio.manager.user.UserManager;
import alfio.model.Event;
import alfio.model.UploadedResource;
import alfio.model.modification.UploadBase64FileModification;
import alfio.model.user.Organization;
import alfio.repository.EventRepository;
import alfio.repository.user.OrganizationRepository;
import alfio.util.TemplateManager;
import alfio.util.TemplateResource;
import com.samskivert.mustache.MustacheException;
import org.apache.commons.lang3.Validate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import static org.springframework.web.bind.annotation.RequestMethod.POST;

@RestController
@RequestMapping("/admin/api")
public class ResourceController {


    private final UploadedResourceManager uploadedResourceManager;
    private final UserManager userManager;
    private final EventRepository eventRepository;
    private final MessageSource messageSource;
    private final TemplateManager templateManager;
    private final OrganizationRepository organizationRepository;
    private final FileUploadManager fileUploadManager;


    @Autowired
    public ResourceController(UploadedResourceManager uploadedResourceManager,
                              UserManager userManager,
                              EventRepository eventRepository,
                              MessageSource messageSource,
                              TemplateManager templateManager,
                              OrganizationRepository organizationRepository,
                              FileUploadManager fileUploadManager) {
        this.uploadedResourceManager = uploadedResourceManager;
        this.userManager = userManager;
        this.eventRepository = eventRepository;
        this.messageSource = messageSource;
        this.templateManager = templateManager;
        this.organizationRepository = organizationRepository;
        this.fileUploadManager = fileUploadManager;
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleSyntaxError(Exception ex) {
        Optional<String> cause = Optional.ofNullable(ex.getCause()).filter(MustacheException.class::isInstance).map(Throwable::getMessage);
        if(cause.isPresent()) {
            return cause.get();
        }
        return "Something went wrong. Please check the syntax and retry";
    }

    @GetMapping(value = "/overridable-template/")
    public List<TemplateResource> getOverridableTemplates() {
        return Stream.of(TemplateResource.values()).filter(TemplateResource::overridable).collect(Collectors.toList());
    }

    @GetMapping(value = "/overridable-template/{name}/{locale}")
    public void getTemplate(@PathVariable("name") TemplateResource name, @PathVariable("locale") String locale, HttpServletResponse response) throws IOException {
        response.setContentType("text/plain");
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try (InputStream is = new ClassPathResource(name.classPath()).getInputStream()) {
            StreamUtils.copy(is, os);
        }
        Locale loc = Locale.forLanguageTag(locale);
        String template = new String(os.toByteArray(), StandardCharsets.UTF_8);

        response.setContentType("text/plain");
        response.getWriter().print(TemplateManager.translate(template, loc, messageSource));
    }

    @PostMapping(value = "/overridable-template/{name}/{locale}/preview")
    public void previewTemplate(@PathVariable("name") TemplateResource name, @PathVariable("locale") String locale,
                                @RequestParam(required = false, value = "organizationId") Integer organizationId,
                                @RequestParam(required = false, value = "eventId") Integer eventId,
                                @RequestBody UploadBase64FileModification template,
                                Principal principal,
                                HttpServletResponse response) throws IOException {


        Locale loc = Locale.forLanguageTag(locale);

        if(organizationId != null && eventId != null) {
            checkAccess(organizationId, eventId, principal);
            Event event = eventRepository.findById(eventId);
            Organization organization = organizationRepository.getById(organizationId);
            Optional<TemplateResource.ImageData> image = TemplateProcessor.extractImageModel(event, fileUploadManager);
            Map<String, Object> model = name.prepareSampleModel(organization, event, image);
            String renderedTemplate = templateManager.renderString(template.getFileAsString(), model, loc, name.getTemplateOutput());
            if("text/plain".equals(name.getRenderedContentType())) {
                response.addHeader("Content-Disposition", "attachment; filename="+name.name()+".txt");
                response.setContentType("text/plain");
                response.setCharacterEncoding("UTF-8");
                try(OutputStream os = response.getOutputStream()) {
                    StreamUtils.copy(renderedTemplate,StandardCharsets.UTF_8, os);
                }
            } else if ("application/pdf".equals(name.getRenderedContentType())) {
                response.setContentType("application/pdf");
                response.addHeader("Content-Disposition", "attachment; filename="+name.name()+".pdf");
                try (OutputStream os = response.getOutputStream()) {
                    TemplateProcessor.prepareItextRenderer(renderedTemplate).createPDF(os);
                }
            } else {
                throw new IllegalStateException("cannot enter here!");
            }
        }
    }


    //------------------

    @GetMapping(value = "/resource/")
    public List<UploadedResource> findAll(Principal principal) {
        checkAccess(principal);
        return uploadedResourceManager.findAll();
    }

    @GetMapping(value = "/resource-organization/{organizationId}")
    public List<UploadedResource> findAllForOrganization(@PathVariable("organizationId") int organizationId, Principal principal) {
        checkAccess(organizationId, principal);
        return uploadedResourceManager.findAll(organizationId);
    }

    @GetMapping(value = "/resource-event/{organizationId}/{eventId}")
    public List<UploadedResource> findAllForEvent(@PathVariable("organizationId") int organizationId, @PathVariable("eventId") int eventId, Principal principal) {
        checkAccess(organizationId, eventId, principal);
        return uploadedResourceManager.findAll(organizationId, eventId);
    }


    //------------------

    @GetMapping(value = "/resource/{name}/metadata")
    public ResponseEntity<UploadedResource> getMetadata(@PathVariable("name") String name, Principal principal) {
        checkAccess(principal);
        if (uploadedResourceManager.hasResource(name)) {
            return new ResponseEntity<>(uploadedResourceManager.get(name), HttpStatus.OK);
        } else {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    @GetMapping(value = "/resource-organization/{organizationId}/{name}/metadata")
    public ResponseEntity<UploadedResource> getMetadata(@PathVariable("organizationId") int organizationId, @PathVariable("name") String name, Principal principal) {
        checkAccess(organizationId, principal);
        if (uploadedResourceManager.hasResource(organizationId, name)) {
            return new ResponseEntity<>(uploadedResourceManager.get(organizationId, name), HttpStatus.OK);
        } else {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    @GetMapping(value = "/resource-event/{organizationId}/{eventId}/{name}/metadata")
    public ResponseEntity<UploadedResource> getMetadata(@PathVariable("organizationId") int organizationId, @PathVariable("eventId") int eventId, @PathVariable("name") String name, Principal principal) {
        checkAccess(organizationId, eventId, principal);
        if (uploadedResourceManager.hasResource(organizationId, eventId, name)) {
            return new ResponseEntity<>(uploadedResourceManager.get(organizationId, eventId, name), HttpStatus.OK);
        } else {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    //------------------

    @PostMapping(value = "/resource/")
    public void uploadFile(@RequestBody UploadBase64FileModification upload, Principal principal) {
        checkAccess(principal);
        uploadedResourceManager.saveResource(upload);
    }

    @PostMapping(value = "/resource-organization/{organizationId}/")
    public void uploadFile(@PathVariable("organizationId") int organizationId, @RequestBody UploadBase64FileModification upload, Principal principal) {
        checkAccess(organizationId, principal);
        uploadedResourceManager.saveResource(organizationId, upload);
    }

    @PostMapping(value = "/resource-event/{organizationId}/{eventId}/")
    public void uploadFile(@PathVariable("organizationId") int organizationId, @PathVariable("eventId") int eventId, @RequestBody UploadBase64FileModification upload, Principal principal) {
        checkAccess(organizationId, eventId, principal);
        uploadedResourceManager.saveResource(organizationId, eventId, upload);
    }

    //------------------
    @GetMapping(value = "/resource/{name:.*}")
    public void outputContent(@PathVariable("name") String name, Principal principal, HttpServletResponse response) throws IOException {
        checkAccess(principal);
        UploadedResource metadata = uploadedResourceManager.get(name);
        try (OutputStream os = response.getOutputStream()) {
            response.setContentType(metadata.getContentType());
            response.setContentLength(metadata.getContentSize());
            uploadedResourceManager.outputResource(name, os);
        }
    }

    @GetMapping(value = "/resource-organization/{organizationId}/{name:.*}")
    public void outputContent(@PathVariable("organizationId") int organizationId, @PathVariable("name") String name, Principal principal, HttpServletResponse response) throws IOException {
        checkAccess(organizationId, principal);
        UploadedResource metadata = uploadedResourceManager.get(organizationId, name);
        try (OutputStream os = response.getOutputStream()) {
            response.setContentType(metadata.getContentType());
            response.setContentLength(metadata.getContentSize());
            uploadedResourceManager.outputResource(organizationId, name, os);
        }
    }

    @GetMapping(value = "/resource-event/{organizationId}/{eventId}/{name:.*}")
    public void outputContent(@PathVariable("organizationId") int organizationId, @PathVariable("eventId") int eventId, @PathVariable("name") String name, Principal principal, HttpServletResponse response) throws IOException {
        checkAccess(organizationId, eventId, principal);
        UploadedResource metadata = uploadedResourceManager.get(organizationId, eventId, name);
        try (OutputStream os = response.getOutputStream()) {
            response.setContentType(metadata.getContentType());
            response.setContentLength(metadata.getContentSize());
            uploadedResourceManager.outputResource(organizationId, eventId, name, os);
        }
    }

    //------------------

    @DeleteMapping(value = "/resource/{name:.*}")
    public void delete(@PathVariable("name") String name, Principal principal) {
        checkAccess(principal);
        uploadedResourceManager.deleteResource(name);
    }

    @DeleteMapping(value = "/resource-organization/{organizationId}/{name:.*}")
    public void delete(@PathVariable("organizationId") int organizationId, @PathVariable("name") String name, Principal principal) {
        checkAccess(organizationId, principal);
        uploadedResourceManager.deleteResource(organizationId, name);
    }

    @DeleteMapping(value = "/resource-event/{organizationId}/{eventId}/{name:.*}")
    public void delete(@PathVariable("organizationId") int organizationId, @PathVariable("eventId") int eventId, @PathVariable("name") String name, Principal principal) {
        checkAccess(organizationId, eventId, principal);
        uploadedResourceManager.deleteResource(organizationId, eventId, name);
    }

    //------------------

    private void checkAccess(Principal principal) {
        Validate.isTrue(userManager.isAdmin(userManager.findUserByUsername(principal.getName())));
    }

    private void checkAccess(int organizationId, Principal principal) {
        Validate.isTrue(userManager.isOwnerOfOrganization(userManager.findUserByUsername(principal.getName()), organizationId));
    }

    private void checkAccess(int organizationId, int eventId, Principal principal) {
        Validate.isTrue(eventRepository.findById(eventId).getOrganizationId() == organizationId && userManager.isOwnerOfOrganization(userManager.findUserByUsername(principal.getName()), organizationId));
    }
}
