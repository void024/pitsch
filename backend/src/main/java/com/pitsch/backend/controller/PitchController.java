package com.pitsch.backend.controller;

import com.pitsch.backend.entity.pitch;
import com.pitsch.backend.Service.PitchService;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/pitches")
public class PitchController {

    private final PitchService pitchService;

    public PitchController(PitchService pitchService) {
        this.pitchService = pitchService;
    }

    // Create a new pitch
    @PostMapping
    public pitch createPitch(@RequestBody pitch pitch) {
        return pitchService.createPitch(pitch);
    }

    // Get all pitches
    @GetMapping
    public List<pitch> getAllPitches() {
        return pitchService.getAllPitches();
    }

    // Get pitch by ID
    @GetMapping("/{id}")
    public Optional<pitch> getPitch(@PathVariable Long id) {
        return pitchService.getPitchById(id);
    }

    // Delete pitch
    @DeleteMapping("/{id}")
    public void deletePitch(@PathVariable Long id) {
        pitchService.deletePitch(id);
    }
}