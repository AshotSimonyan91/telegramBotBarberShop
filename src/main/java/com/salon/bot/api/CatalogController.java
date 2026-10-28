package com.salon.bot.api;

import com.salon.bot.api.dto.MasterDto;
import com.salon.bot.api.dto.ServiceDto;
import com.salon.bot.api.dto.SlotsResponse;
import com.salon.bot.model.Master;
import com.salon.bot.model.Service;
import com.salon.bot.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Справочники и расписание: услуги, мастера, свободное время, рабочие даты. */
@ConditionalOnApi
@RestController
@RequestMapping(path = ApiPaths.BASE, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class CatalogController {

    private final BookingService bookingService;
    private final BookingApiService api;

    @GetMapping("/services")
    public List<ServiceDto> services() {
        return bookingService.services().stream().map(ServiceDto::from).toList();
    }

    /** Только активные мастера. */
    @GetMapping("/masters")
    public List<MasterDto> masters() {
        return bookingService.activeMasters().stream().map(MasterDto::from).toList();
    }

    /** Свободное время на дату; без masterName — объединение по всем активным мастерам. */
    @GetMapping("/slots")
    public SlotsResponse slots(@RequestParam(required = false) String serviceName,
                               @RequestParam(required = false) String date,
                               @RequestParam(required = false) String masterName) {
        LocalDate day = api.requireDate("date", date);
        Service service = api.requireService(serviceName);
        List<Master> masters = api.mastersFor(masterName);
        return new SlotsResponse(day, service.getName(), service.getDurationMinutes(),
                bookingService.freeSlots(masters, day, service.getDurationMinutes()));
    }

    /** Ближайшие рабочие даты (хотя бы один мастер работает). serviceName необязателен, но проверяется. */
    @GetMapping("/dates")
    public List<LocalDate> dates(@RequestParam(required = false) String serviceName,
                                 @RequestParam(required = false) String masterName,
                                 @RequestParam(required = false) String days) {
        int count = api.parseDays(days);
        if (serviceName != null && !serviceName.isBlank()) {
            api.requireService(serviceName);
        }
        return bookingService.availableDates(api.mastersFor(masterName), count);
    }
}
