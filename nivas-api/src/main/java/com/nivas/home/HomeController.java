package com.nivas.home;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nivas.home.dto.HomeDtos.HomePage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/home")
@Tag(name = "Home", description = "The national and per-city landing pages")
public class HomeController {

    private final HomeService homeService;

    public HomeController(HomeService homeService) {
        this.homeService = homeService;
    }

    @GetMapping
    @Operation(summary = "The all-India home page")
    public HomePage national() {
        return homeService.national();
    }

    /**
     * A city's home page.
     *
     * <p>The city is a path variable rather than a query parameter because these
     * are real, indexable pages — {@code /mumbai} is a URL somebody links to,
     * and search engines treat a path far more kindly than a query string.
     */
    @GetMapping("/{city}")
    @Operation(summary = "A city home page, which may be under an exclusive takeover")
    public HomePage city(@PathVariable @Size(min = 2, max = 80) String city) {
        return homeService.forCity(city);
    }
}
