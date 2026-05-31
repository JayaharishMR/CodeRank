package com.coderank.controller;

import com.coderank.model.enums.Language;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Arrays;
import java.util.List;

/**
 * Exposes the set of supported languages so the frontend can populate
 * dropdowns dynamically rather than hard-coding language options.
 */
@RestController
@RequestMapping("/api/v1")
public class LanguageController {
    @GetMapping("/languages")
    public List<Language> getLanguages() {
        return Arrays.asList(Language.values());
    }
}
