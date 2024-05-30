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
package alfio.controller.api.admin;

import alfio.controller.api.support.DescriptionsLoader;
import alfio.controller.api.support.EventListItem;
import alfio.controller.api.support.TicketHelper;
import alfio.controller.support.TemplateProcessor;
import alfio.manager.*;
import alfio.manager.i18n.I18nManager;
import alfio.manager.user.UserManager;
import alfio.model.*;
import alfio.model.modification.*;
import alfio.model.result.ValidationResult;
import alfio.model.user.Organization;
import alfio.model.user.Role;
import alfio.repository.DynamicFieldTemplateRepository;
import alfio.repository.SponsorScanRepository;
import alfio.repository.TicketCategoryDescriptionRepository;
import alfio.repository.TicketFieldRepository;
import alfio.util.Json;
import alfio.util.MonetaryUtil;
import alfio.util.TemplateManager;
import alfio.util.Validator;
import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.util.StreamUtils;
import org.springframework.validation.Errors;
import org.springframework.web.bind.annotation.*;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static alfio.util.OptionalWrapper.optionally;
import static alfio.util.Validator.*;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
@RestController
@RequestMapping("/admin/api")
@Log4j2
public class EventApiController {

    private static final String OK = "OK";
    private final EventManager eventManager;
    private final EventStatisticsManager eventStatisticsManager;
    private final I18nManager i18nManager;
    private final TicketReservationManager ticketReservationManager;
    private final TicketCategoryDescriptionRepository ticketCategoryDescriptionRepository;
    private final TicketFieldRepository ticketFieldRepository;
    private final DescriptionsLoader descriptionsLoader;
    private final TicketHelper ticketHelper;
    private final DynamicFieldTemplateRepository dynamicFieldTemplateRepository;
    private final UserManager userManager;
    private final SponsorScanRepository sponsorScanRepository;
    private final PaymentManager paymentManager;
    private final TemplateManager templateManager;
    private final FileUploadManager fileUploadManager;

    @Autowired
    public EventApiController(EventManager eventManager,
                              EventStatisticsManager eventStatisticsManager,
                              I18nManager i18nManager,
                              TicketReservationManager ticketReservationManager,
                              TicketCategoryDescriptionRepository ticketCategoryDescriptionRepository,
                              TicketFieldRepository ticketFieldRepository,
                              DescriptionsLoader descriptionsLoader,
                              TicketHelper ticketHelper,
                              DynamicFieldTemplateRepository dynamicFieldTemplateRepository,
                              UserManager userManager,
                              SponsorScanRepository sponsorScanRepository,
                              PaymentManager paymentManager,
                              TemplateManager templateManager,
                              FileUploadManager fileUploadManager) {
        this.eventManager = eventManager;
        this.eventStatisticsManager = eventStatisticsManager;
        this.i18nManager = i18nManager;
        this.ticketReservationManager = ticketReservationManager;
        this.ticketCategoryDescriptionRepository = ticketCategoryDescriptionRepository;
        this.ticketFieldRepository = ticketFieldRepository;
        this.descriptionsLoader = descriptionsLoader;
        this.ticketHelper = ticketHelper;
        this.dynamicFieldTemplateRepository = dynamicFieldTemplateRepository;
        this.userManager = userManager;
        this.sponsorScanRepository = sponsorScanRepository;
        this.paymentManager = paymentManager;
        this.templateManager = templateManager;
        this.fileUploadManager = fileUploadManager;
    }

    @ExceptionHandler(DataAccessException.class)
    public String exception(DataAccessException e) {
        log.warn("unhandled exception", e);
        return "unexpected error. More info in the application log";
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String unhandledException(Exception e) {
        if(!IllegalArgumentException.class.isInstance(e)) {
            log.warn("unhandled exception", e);
        }
        return e.getMessage();
    }


    @GetMapping(value = "/paymentProxies/{organizationId}")
    @ResponseStatus(HttpStatus.OK)
    public List<PaymentManager.PaymentMethod> getPaymentProxies(@PathVariable("organizationId") int organizationId, Principal principal) {
        return userManager.findUserOrganizations(principal.getName())
            .stream()
            .filter(o -> o.getId() == organizationId)
            .findFirst()
            .map(o -> paymentManager.getPaymentMethods(o.getId()))
            .orElse(Collections.emptyList());
    }

    @GetMapping(value = "/events", headers = "Authorization")
    public List<EventListItem> getAllEventsForExternal(Principal principal, HttpServletRequest request) {
        List<Integer> userOrganizations = userManager.findUserOrganizations(principal.getName()).stream().map(Organization::getId).collect(Collectors.toList());
        return eventManager.getActiveEvents().stream()
            .filter(e -> userOrganizations.contains(e.getOrganizationId()))
            .sorted(Comparator.comparing(e -> e.getBegin().withZoneSameInstant(ZoneId.systemDefault())))
            .map(s -> new EventListItem(s, request.getContextPath(), descriptionsLoader.eventDescriptions()))
            .collect(Collectors.toList());
    }

    @GetMapping(value = "/events")
    public List<EventStatistic> getAllEvents(Principal principal) {
        return eventStatisticsManager.getAllEventsWithStatistics(principal.getName());
    }

    @GetMapping(value = "/active-events")
    public List<EventStatistic> getAllActiveEvents(Principal principal) {
        return eventStatisticsManager.getAllEventsWithStatisticsFilteredBy(principal.getName(), event -> !event.expiredSince(14));
    }

    @GetMapping(value = "/expired-events")
    public List<EventStatistic> getAllExpiredEvents(Principal principal) {
        return eventStatisticsManager.getAllEventsWithStatisticsFilteredBy(principal.getName(), event -> event.expiredSince(14));
    }

    @GetMapping(value = "/events/{name}")
    public ResponseEntity<Map<String, Object>> getSingleEvent(@PathVariable("name") String eventName, Principal principal) {
        final String username = principal.getName();
        return optionally(() -> eventStatisticsManager.getSingleEventWithStatistics(eventName, username))
            .map(event -> {
                Map<String, Object> out = new HashMap<>();
                out.put("event", event);
                out.put("organization", eventManager.loadOrganizer(event.getEvent(), username));
                return ResponseEntity.ok(out);
            }).orElseGet(() -> new ResponseEntity<>(HttpStatus.NOT_FOUND));
    }
    
    @DeleteMapping(value ="/events/{eventId}")
    public void deleteEvent(@PathVariable("eventId") int eventId, Principal principal) {
    	eventManager.deleteEvent(eventId, principal.getName());
    }

    @GetMapping(value = "/events/id/{eventId}")
    public Event getSingleEventById(@PathVariable("eventId") int eventId, Principal principal) {
        return eventManager.getSingleEventById(eventId, principal.getName());
    }

    @PostMapping(value = "/events/check")
    public ValidationResult validateEvent(@RequestBody EventModification eventModification, Errors errors) {
        ValidationResult base = validateEventHeader(Optional.empty(), eventModification, errors)
            .or(validateEventPrices(Optional.empty(), eventModification, errors))
            .or(eventModification.getAdditionalServices().stream().map(as -> validateAdditionalService(as, eventModification, errors)).reduce(ValidationResult::or).orElse(ValidationResult.success()));
        AtomicInteger counter = new AtomicInteger();
        return base.or(eventModification.getTicketCategories().stream()
            .map(c -> validateCategory(c, errors, "ticketCategories[" + counter.getAndIncrement() + "]."))
            .reduce(ValidationResult::or)
            .orElse(ValidationResult.success())).or(validateAdditionalTicketFields(eventModification.getTicketFields(), errors));
    }

    private ValidationResult validateAdditionalTicketFields(List<EventModification.AdditionalField> ticketFields, Errors errors) {
        //meh
        AtomicInteger cnt = new AtomicInteger();
        return Optional.ofNullable(ticketFields).orElseGet(Collections::emptyList).stream().map(field -> {
            String prefix = "ticketFields["+cnt.getAndIncrement()+"]";
            if (StringUtils.isBlank(field.getName())) {
                errors.rejectValue(prefix + ".name", "error.required");
            }
            //TODO: check label value is present for all the locales
            //TODO: for select check option value+label

            return Validator.evaluateValidationResult(errors);
        }).reduce(ValidationResult::or).orElseGet(ValidationResult::success);
    }

    @PostMapping(value = "/events/new")
    public String insertEvent(@RequestBody EventModification eventModification) {
        eventManager.createEvent(eventModification);
        return OK;
    }

    @PutMapping(value = "/events/{id}/status")
    public String activateEvent(@PathVariable("id") int id, @RequestParam("active") boolean active, Principal principal) {
        eventManager.toggleActiveFlag(id, principal.getName(), active);
        return OK;
    }

    @PostMapping(value = "/events/{id}/header/update")
    public ValidationResult updateHeader(@PathVariable("id") int id, @RequestBody EventModification eventModification, Errors errors,  Principal principal) {
        Event event = eventManager.getSingleEventById(id, principal.getName());
        return validateEventHeader(Optional.of(event), eventModification, errors).ifSuccess(() -> eventManager.updateEventHeader(event, eventModification, principal.getName()));
    }

    @PostMapping(value = "/events/{id}/prices/update")
    public ValidationResult updatePrices(@PathVariable("id") int id, @RequestBody EventModification eventModification, Errors errors,  Principal principal) {
        Event event = eventManager.getSingleEventById(id, principal.getName());
        return validateEventPrices(Optional.of(event), eventModification, errors).ifSuccess(() -> eventManager.updateEventPrices(event, eventModification, principal.getName()));
    }

    @PostMapping(value = "/events/{eventId}/categories/{categoryId}/update")
    public ValidationResult updateExistingCategory(@PathVariable("eventId") int eventId, @PathVariable("categoryId") int categoryId, @RequestBody TicketCategoryModification category, Errors errors, Principal principal) {
        return validateCategory(category, errors).ifSuccess(() -> eventManager.updateCategory(categoryId, eventId, category, principal.getName()));
    }

    @PostMapping(value = "/events/{eventId}/categories/new")
    public ValidationResult createCategory(@PathVariable("eventId") int eventId, @RequestBody TicketCategoryModification category, Errors errors, Principal principal) {
        return validateCategory(category, errors).ifSuccess(() -> eventManager.insertCategory(eventId, category, principal.getName()));
    }
    
    @PutMapping(value = "/events/reallocate")
    public String reallocateTickets(@RequestBody TicketAllocationModification form) {
        eventManager.reallocateTickets(form.getSrcCategoryId(), form.getTargetCategoryId(), form.getEventId());
        return OK;
    }

    @PutMapping(value = "/events/{eventName}/category/{categoryId}/unbind-tickets")
    public String unbindTickets(@PathVariable("eventName") String eventName, @PathVariable("categoryId") int categoryId, Principal principal) {
        eventManager.unbindTickets(eventName, categoryId, principal.getName());
        return OK;
    }

    private static final List<String> FIXED_FIELDS = Arrays.asList("ID", "Creation", "Category", "Event", "Status", "OriginalPrice", "PaidPrice", "Discount", "VAT", "ReservationID", "Full Name", "First Name", "Last Name", "E-Mail", "Locked", "Language", "Confirmation", "Billing Address");
    private static final int[] BOM_MARKERS = new int[] {0xEF, 0xBB, 0xBF};

    @RequestMapping("/events/{eventName}/export.csv")
    public void downloadAllTicketsCSV(@PathVariable("eventName") String eventName, HttpServletRequest request, HttpServletResponse response, Principal principal) throws IOException {
        List<String> fields = Arrays.asList(Optional.ofNullable(request.getParameterValues("fields")).orElse(new String[] {}));
        Event event = loadEvent(eventName, principal);
        Map<Integer, TicketCategory> categoriesMap = eventManager.loadTicketCategories(event).stream().collect(Collectors.toMap(TicketCategory::getId, Function.identity()));
        ZoneId eventZoneId = event.getZoneId();

        Predicate<String> contains = FIXED_FIELDS::contains;

        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=" + eventName + "-export.csv");

        try(ServletOutputStream out = response.getOutputStream(); CSVWriter writer = new CSVWriter(new OutputStreamWriter(out))) {

            for (int marker : BOM_MARKERS) {//UGLY-MODE_ON: specify that the file is written in UTF-8 with BOM, thanks to alexr http://stackoverflow.com/a/4192897
                out.write(marker);
            }
            
            writer.writeNext(fields.toArray(new String[fields.size()]));

            eventManager.findAllConfirmedTicketsForCSV(eventName, principal.getName()).stream().map(t -> {
                List<String> line = new ArrayList<>();
                if(fields.contains("ID")) {line.add(t.getUuid());}
                if(fields.contains("Creation")) {line.add(t.getCreation().withZoneSameInstant(eventZoneId).toString());}
                if(fields.contains("Category")) {line.add(categoriesMap.get(t.getCategoryId()).getName());}
                if(fields.contains("Event")) {line.add(eventName);}
                if(fields.contains("Status")) {line.add(t.getStatus().toString());}
                if(fields.contains("OriginalPrice")) {line.add(MonetaryUtil.centsToUnit(t.getSrcPriceCts()).toString());}
                if(fields.contains("PaidPrice")) {line.add(MonetaryUtil.centsToUnit(t.getFinalPriceCts()).toString());}
                if(fields.contains("Discount")) {line.add(MonetaryUtil.centsToUnit(t.getDiscountCts()).toString());}
                if(fields.contains("VAT")) {line.add(MonetaryUtil.centsToUnit(t.getVatCts()).toString());}
                if(fields.contains("ReservationID")) {line.add(t.getTicketsReservationId());}
                if(fields.contains("Full Name")) {line.add(t.getFullName());}
                if(fields.contains("First Name")) {line.add(t.getFirstName());}
                if(fields.contains("Last Name")) {line.add(t.getLastName());}
                if(fields.contains("E-Mail")) {line.add(t.getEmail());}
                if(fields.contains("Locked")) {line.add(String.valueOf(t.getLockedAssignment()));}
                if(fields.contains("Language")) {line.add(String.valueOf(t.getUserLanguage()));}
                if(fields.contains("Confirmation")) {line.add(t.getTicketReservation().getConfirmationTimestamp().withZoneSameInstant(eventZoneId).toString());}
                if(fields.contains("Billing Address")) {line.add(t.getTicketReservation().getBillingAddress());}

                //obviously not optimized
                Map<String, String> additionalValues = ticketFieldRepository.findAllValuesForTicketId(t.getId());

                fields.stream().filter(contains.negate()).forEachOrdered(field -> {
                    line.add(additionalValues.getOrDefault(field, "").replaceAll("\"", ""));
                });

                return line.toArray(new String[line.size()]);
            }).forEachOrdered(writer::writeNext);
            writer.flush();
            out.flush();
        }
    }

    @RequestMapping("/events/{eventName}/sponsor-scan/export.csv")
    public void downloadSponsorScanExport(@PathVariable("eventName") String eventName, HttpServletResponse response, Principal principal) throws IOException {
        Event event = loadEvent(eventName, principal);
        List<TicketFieldConfiguration> fields = ticketFieldRepository.findAdditionalFieldsForEvent(event.getId());

        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=" + eventName + "-sponsor-scan.csv");

        try(ServletOutputStream out = response.getOutputStream(); CSVWriter writer = new CSVWriter(new OutputStreamWriter(out))) {
            for (int marker : BOM_MARKERS) {
                out.write(marker);
            }
            
            List<String> header = new ArrayList<>();
            header.add("Username");
            header.add("Timestamp");
            header.add("Full name");
            header.add("Email");
            header.addAll(fields.stream().map(TicketFieldConfiguration::getName).collect(Collectors.toList()));
            writer.writeNext(header.toArray(new String[header.size()]));
            userManager.findAllUsers(principal.getName()).stream()
                .map(u -> Pair.of(u, userManager.getUserRole(u)))
                .filter(p -> p.getRight() == Role.SPONSOR)
                .flatMap(p -> sponsorScanRepository.loadSponsorData(event.getId(), p.getKey().getId(), SponsorScanRepository.DEFAULT_TIMESTAMP)
                    .stream()
                    .map(v -> Pair.of(v, ticketFieldRepository.findAllValuesForTicketId(v.getTicket().getId()))))
                .map(p -> {
                    DetailedScanData data = p.getLeft();
                    Map<String, String> descriptions = p.getRight();
                    return Pair.of(data, fields.stream().map(x -> descriptions.getOrDefault(x.getName(), "")).collect(Collectors.toList()));
                }).map(p -> {
                    List<String> line = new ArrayList<>();
                    Ticket ticket = p.getLeft().getTicket();
                    SponsorScan sponsorScan = p.getLeft().getSponsorScan();
                    line.add(userManager.findUser(sponsorScan.getUserId()).getUsername());
                    line.add(sponsorScan.getTimestamp().toString());
                    line.add(ticket.getFullName());
                    line.add(ticket.getEmail());
                    line.addAll(p.getRight());
                    return line.toArray(new String[line.size()]);
                }).forEachOrdered(writer::writeNext);
            writer.flush();
            out.flush();
        }
    }

    @RequestMapping("/events/{eventName}/fields")
    public List<String> getAllFields(@PathVariable("eventName") String eventName) {
        List<String> fields = new ArrayList<>();
        fields.addAll(FIXED_FIELDS);
        fields.addAll(ticketFieldRepository.findFieldsForEvent(eventName));
        return fields;
    }

    @RequestMapping("/events/{eventName}/additional-field")
    public List<TicketFieldConfigurationAndAllDescriptions> getAllAdditionalField(@PathVariable("eventName") String eventName) {
        final Map<Integer, List<TicketFieldDescription>> descById = ticketFieldRepository.findDescriptions(eventName).stream().collect(Collectors.groupingBy(TicketFieldDescription::getTicketFieldConfigurationId));
        return ticketFieldRepository.findAdditionalFieldsForEvent(eventName).stream()
            .map(field -> new TicketFieldConfigurationAndAllDescriptions(field, descById.getOrDefault(field.getId(), Collections.emptyList())))
            .collect(Collectors.toList());
    }

    @GetMapping(value = "/event/additional-field/templates")
    public List<DynamicFieldTemplate> loadTemplates() {
        return dynamicFieldTemplateRepository.loadAll();
    }

    @PostMapping(value = "/events/{eventName}/additional-field/descriptions")
    public void saveAdditionalFieldDescriptions(@RequestBody Map<String, TicketFieldDescriptionModification> descriptions) {
        eventManager.updateTicketFieldDescriptions(descriptions);
    }
    
    @PostMapping(value = "/events/{eventName}/additional-field/new")
    public ValidationResult addAdditionalField(@PathVariable("eventName") String eventName, @RequestBody EventModification.AdditionalField field, Principal principal, Errors errors) {
        Event event = eventManager.getSingleEvent(eventName, principal.getName());
        List<TicketFieldConfiguration> fields = ticketFieldRepository.findAdditionalFieldsForEvent(event.getId());
        return validateAdditionalFields(fields, field, errors).ifSuccess(() -> eventManager.addAdditionalField(event, field));
    }
    
    @PostMapping(value = "/events/{eventName}/additional-field/swap-position/{id1}/{id2}")
    public void swapAdditionalFieldPosition(@PathVariable("eventName") String eventName, @PathVariable("id1") int id1, @PathVariable("id2") int id2, Principal principal) {
    	Event event = eventManager.getSingleEvent(eventName, principal.getName());
    	eventManager.swapAdditionalFieldPosition(event.getId(), id1, id2);
    }
    
    @DeleteMapping(value = "/events/{eventName}/additional-field/{id}")
    public void deleteAdditionalField(@PathVariable("eventName") String eventName, @PathVariable("id") int id) {
    	eventManager.deleteAdditionalField(id);
    }

    @RequestMapping(value = "/events/{eventName}/pending-payments")
    public List<SerializablePair<TicketReservation, OrderSummary>> getPendingPayments(@PathVariable("eventName") String eventName, Principal principal) {
        return ticketReservationManager.getPendingPayments(eventStatisticsManager.getSingleEventWithStatistics(eventName, principal.getName())).stream()
                .map(SerializablePair::fromPair).collect(Collectors.toList());
    }

    @PostMapping(value = "/events/{eventName}/pending-payments/{reservationId}/confirm")
    public String confirmPayment(@PathVariable("eventName") String eventName, @PathVariable("reservationId") String reservationId, Principal principal,
                                 Model model, HttpServletRequest request) {
        ticketReservationManager.confirmOfflinePayment(loadEvent(eventName, principal), reservationId);
        ticketReservationManager.findById(reservationId)
            .filter(TicketReservation::isDirectAssignmentRequested)
            .ifPresent(reservation -> ticketHelper.directTicketAssignment(eventName, reservationId, reservation.getEmail(), reservation.getFullName(), reservation.getFirstName(), reservation.getLastName(), reservation.getUserLanguage(), Optional.empty(), request, model));
        return OK;
    }

    @DeleteMapping(value = "/events/{eventName}/pending-payments/{reservationId}")
    public String deletePendingPayment(@PathVariable("eventName") String eventName, @PathVariable("reservationId") String reservationId, Principal principal) {
        ticketReservationManager.deleteOfflinePayment(loadEvent(eventName, principal), reservationId, false);
        return OK;
    }

    @PostMapping(value = "/events/{eventName}/pending-payments/bulk-confirmation")
    public List<Triple<Boolean, String, String>> bulkConfirmation(@PathVariable("eventName") String eventName,
                                                                  Principal principal,
                                                                  @RequestBody UploadBase64FileModification file) throws IOException {

        try(InputStreamReader isr = new InputStreamReader(file.getInputStream()); CSVReader reader = new CSVReader(isr)) {
            Event event = loadEvent(eventName, principal);
            return reader.readAll().stream()
                    .map(line -> {
                        String reservationID = null;
                        try {
                            Validate.isTrue(line.length >= 2);
                            reservationID = line[0];
                            ticketReservationManager.validateAndConfirmOfflinePayment(reservationID, event, new BigDecimal(line[1]));
                            return Triple.of(Boolean.TRUE, reservationID, "");
                        } catch (Exception e) {
                            return Triple.of(Boolean.FALSE, Optional.ofNullable(reservationID).orElse(""), e.getMessage());
                        }
                    })
                    .collect(Collectors.toList());
        }
    }

    @PutMapping(value = "/events/{eventName}/categories/{categoryId}/tickets/{ticketId}/toggle-locking")
    public boolean toggleTicketLocking(@PathVariable("eventName") String eventName,
                                       @PathVariable("categoryId") int categoryId,
                                       @PathVariable("ticketId") int ticketId,
                                       Principal principal) {
        return eventManager.toggleTicketLocking(eventName, categoryId, ticketId, principal.getName());
    }

    @GetMapping(value = "/events/{eventName}/languages")
    public List<ContentLanguage> getAvailableLocales(@PathVariable("eventName") String eventName) {
        return i18nManager.getEventLanguages(eventName);
    }

    @GetMapping(value = "/events/{eventName}/all-invoices")
    public void getAllInvoices(@PathVariable("eventName") String eventName, HttpServletResponse response, Principal principal) throws  IOException {
        Event event = loadEvent(eventName, principal);

        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=" + eventName + "-invoices.zip");

        try(OutputStream os = response.getOutputStream(); ZipOutputStream zipOS = new ZipOutputStream(os)) {
            for (TicketReservation reservation : ticketReservationManager.findAllInvoices(event.getId())) {
                OrderSummary orderSummary = Json.fromJson(reservation.getInvoiceModel(), OrderSummary.class);
                Optional<String> vat = Optional.ofNullable(orderSummary.getVatPercentage());
                Map<String, Object> reservationModel = ticketReservationManager.prepareModelForReservationEmail(event, reservation, vat, orderSummary);
                Optional<byte[]> pdf = TemplateProcessor.buildInvoicePdf(event, fileUploadManager, new Locale(reservation.getUserLanguage()), templateManager, reservationModel);

                if(pdf.isPresent()) {
                    zipOS.putNextEntry(new ZipEntry("invoice-" + eventName + "-id-" + reservation.getId() + "-invoice-nr-" + reservation.getInvoiceNumber() + ".pdf"));
                    StreamUtils.copy(pdf.get(), zipOS);
                }
            }
        }
    }

    @GetMapping(value = "/events-all-languages")
    public List<ContentLanguage> getAllLanguages() {
        return i18nManager.getAvailableLanguages();
    }

    @GetMapping(value = "/events-supported-languages")
    public List<ContentLanguage> getSupportedLanguages() {
        return i18nManager.getSupportedLanguages();
    }

    @GetMapping(value = "/events/{eventName}/categories-containing-tickets")
    public List<TicketCategoryModification> getCategoriesWithTickets(@PathVariable("eventName") String eventName, Principal principal) {
        Event event = loadEvent(eventName, principal);
        return eventStatisticsManager.loadTicketCategoriesWithStats(event).stream()
                .filter(tc -> !tc.getTickets().isEmpty())
                .map(tc -> TicketCategoryModification.fromTicketCategory(tc.getTicketCategory(), ticketCategoryDescriptionRepository.findByTicketCategoryId(tc.getId()), event.getZoneId()))
                .collect(Collectors.toList());
    }

    private Event loadEvent(String eventName, Principal principal) {
        Optional<Event> singleEvent = optionally(() -> eventManager.getSingleEvent(eventName, principal.getName()));
        Validate.isTrue(singleEvent.isPresent(), "event not found");
        return singleEvent.get();
    }

}
