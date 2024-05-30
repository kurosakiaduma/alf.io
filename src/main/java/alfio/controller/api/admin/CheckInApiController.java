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

import alfio.manager.CheckInManager;
import alfio.manager.support.TicketAndCheckInResult;
import alfio.model.FullTicketInfo;
import lombok.Data;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

@Log4j2
@RestController
@RequestMapping("/admin/api")
public class CheckInApiController {

    private final CheckInManager checkInManager;

    @Data
    public static class TicketCode {
        private String code;
    }
    
    @Autowired
    public CheckInApiController(CheckInManager checkInManager) {
        this.checkInManager = checkInManager;
    }
    
    @GetMapping(value = "/check-in/{eventId}/ticket/{ticketIdentifier}")
    public TicketAndCheckInResult findTicketWithUUID(@PathVariable("eventId") int eventId, @PathVariable("ticketIdentifier") String ticketIdentifier, @RequestParam("qrCode") String qrCode) {
        return checkInManager.evaluateTicketStatus(eventId, ticketIdentifier, Optional.ofNullable(qrCode));
    }

    @GetMapping(value = "/check-in/event/{eventName}/ticket/{ticketIdentifier}")
    public TicketAndCheckInResult findTicketWithUUID(@PathVariable("eventName") String eventName, @PathVariable("ticketIdentifier") String ticketIdentifier, @RequestParam("qrCode") String qrCode) {
        return checkInManager.evaluateTicketStatus(eventName, ticketIdentifier, Optional.ofNullable(qrCode));
    }

    @PostMapping(value = "/check-in/{eventId}/ticket/{ticketIdentifier}")
    public TicketAndCheckInResult checkIn(@PathVariable("eventId") int eventId,
                                          @PathVariable("ticketIdentifier") String ticketIdentifier,
                                          @RequestBody TicketCode ticketCode,
                                          Principal principal) {
        return checkInManager.checkIn(eventId, ticketIdentifier, Optional.ofNullable(ticketCode).map(TicketCode::getCode), principal.getName());
    }

    @PostMapping(value = "/check-in/event/{eventName}/ticket/{ticketIdentifier}")
    public TicketAndCheckInResult checkIn(@PathVariable("eventName") String eventName,
                                          @PathVariable("ticketIdentifier") String ticketIdentifier,
                                          @RequestBody TicketCode ticketCode,
                                          @RequestParam(value = "offlineUser", required = false) String offlineUser,
                                          Principal principal) {
        String user = StringUtils.defaultIfBlank(offlineUser, principal.getName());
        return checkInManager.checkIn(eventName, ticketIdentifier, Optional.ofNullable(ticketCode).map(TicketCode::getCode), user);
    }

    @PostMapping(value = "/check-in/{eventId}/ticket/{ticketIdentifier}/manual-check-in")
    public boolean manualCheckIn(@PathVariable("eventId") int eventId,
                                 @PathVariable("ticketIdentifier") String ticketIdentifier,
                                 Principal principal) {
        log.warn("for event id : {} and ticket : {}, a manual check in has been done", eventId, ticketIdentifier);
        return checkInManager.manualCheckIn(eventId, ticketIdentifier, principal.getName());
    }

    @PostMapping(value = "/check-in/{eventId}/ticket/{ticketIdentifier}/revert-check-in")
    public boolean revertCheckIn(@PathVariable("eventId") int eventId,
                                 @PathVariable("ticketIdentifier") String ticketIdentifier,
                                 Principal principal) {
        log.warn("for event id : {} and ticket : {}, a revert of the check in has been done", eventId, ticketIdentifier);
        return checkInManager.revertCheckIn(eventId, ticketIdentifier, principal.getName());
    }

    @PostMapping(value = "/check-in/event/{eventName}/ticket/{ticketIdentifier}/confirm-on-site-payment")
    public TicketAndCheckInResult confirmOnSitePayment(@PathVariable("eventName") String eventName,
                                                       @PathVariable("ticketIdentifier") String ticketIdentifier,
                                                       @RequestBody TicketCode ticketCode,
                                                       @RequestParam(value = "offlineUser", required = false) String offlineUser,
                                                       Principal principal) {
        String user = StringUtils.defaultIfBlank(offlineUser, principal.getName());
        return checkInManager.confirmOnSitePayment(eventName, ticketIdentifier, Optional.ofNullable(ticketCode).map(TicketCode::getCode), user);
    }
    
    @PostMapping(value = "/check-in/{eventId}/ticket/{ticketIdentifier}/confirm-on-site-payment")
    public OnSitePaymentConfirmation confirmOnSitePayment(@PathVariable("eventId") int eventId, @PathVariable("ticketIdentifier") String ticketIdentifier) {
        return checkInManager.confirmOnSitePayment(ticketIdentifier)
            .map(s -> new OnSitePaymentConfirmation(true, "ok"))
            .orElseGet(() -> new OnSitePaymentConfirmation(false, "Ticket with uuid " + ticketIdentifier + " not found"));
    }
    
    @GetMapping(value = "/check-in/{eventId}/ticket")
    public List<FullTicketInfo> listAllTickets(@PathVariable("eventId") int eventId) {
        return checkInManager.findAllFullTicketInfo(eventId);
    }

    @GetMapping(value = "/check-in/{eventName}/offline")
    public Map<String, String> getOfflineEncryptedInfo(@PathVariable("eventName") String eventName) {
        return checkInManager.getEncryptedAttendeesInformation(eventName, Collections.singleton("company"));
    }

    @Data
    public static class OnSitePaymentConfirmation {
        private final boolean status;
        private final String message;
    }
}
