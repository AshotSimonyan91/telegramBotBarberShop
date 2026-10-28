package com.salon.bot.api;

import com.salon.bot.api.dto.BookingDto;
import com.salon.bot.api.dto.NewBookingRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/** Записи: список с фильтрами, просмотр, создание администратором, отмена. */
@ConditionalOnApi
@RestController
@RequestMapping(path = ApiPaths.BASE + "/bookings", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class BookingsController {

    /** Заголовок ответа на отмену: true/false — доставлено ли уведомление клиенту в Telegram. */
    static final String CLIENT_NOTIFIED_HEADER = "X-Client-Notified";

    private final BookingApiService api;

    @GetMapping
    public List<BookingDto> list(@RequestParam(required = false) String date,
                                 @RequestParam(required = false) String from,
                                 @RequestParam(required = false) String to,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(required = false) String q,
                                 @RequestParam(required = false) String masterName) {
        return api.findBookings(date, from, to, status, q, masterName).stream().map(BookingDto::from).toList();
    }

    @GetMapping("/{id}")
    public BookingDto get(@PathVariable long id) {
        return BookingDto.from(api.getBooking(id));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BookingDto> create(@RequestBody NewBookingRequest request) {
        BookingDto booking = BookingDto.from(api.create(request));
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}").buildAndExpand(booking.id()).toUri();
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(booking);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<BookingDto> cancel(@PathVariable long id) {
        BookingApiService.CancelResult result = api.cancel(id);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.notifyAttempted()) {
            response.header(CLIENT_NOTIFIED_HEADER, String.valueOf(result.notified()));
        }
        return response.body(BookingDto.from(result.booking()));
    }
}
