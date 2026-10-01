package com.pitsch.backend.Service;

import com.pitsch.backend.entity.pitch;
import com.pitsch.backend.repository.PitchRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class PitchService {

    private final PitchRepository pitchRepository;

    public PitchService(PitchRepository pitchRepository) {
        this.pitchRepository = pitchRepository;
    }

    // Create a new pitch
    public pitch createPitch(pitch pitch) {

        pitch.setCreatedAt(LocalDateTime.now());

        if (pitch.getStatus() == null || pitch.getStatus().isEmpty()) {
            pitch.setStatus("NEW");
        }

        return pitchRepository.save(pitch);
    }

    // Get all pitches
    public List<pitch> getAllPitches() {
        return pitchRepository.findAll();
    }

    // Get pitch by ID
    public Optional<pitch> getPitchById(Long id) {
        return pitchRepository.findById(id);
    }

    // Delete pitch
    public void deletePitch(Long id) {
        pitchRepository.deleteById(id);
    }
}