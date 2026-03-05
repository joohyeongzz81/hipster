package com.hipster.domain;

import com.hipster.descriptor.domain.Descriptor;
import com.hipster.descriptor.repository.DescriptorRepository;
import com.hipster.genre.domain.Genre;
import com.hipster.genre.repository.GenreRepository;
import com.hipster.global.domain.Language;
import com.hipster.location.domain.Location;
import com.hipster.location.repository.LocationRepository;
import com.hipster.release.domain.Release;
import com.hipster.release.domain.ReleaseDescriptor;
import com.hipster.release.domain.ReleaseGenre;
import com.hipster.release.domain.ReleaseLanguage;
import com.hipster.release.domain.ReleaseType;
import com.hipster.release.repository.ReleaseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class DomainIntegrityTest {

    @Autowired private TestEntityManager em;
    @Autowired private ReleaseRepository releaseRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private DescriptorRepository descriptorRepository;
    @Autowired private LocationRepository locationRepository;

    @Test
    @DisplayName("Release에 연결된 Genre, Descriptor, Location, Language가 정상적으로 영속화되고 조회된다.")
    void testReleaseDomainRelationships() {
        // given
        Location location = locationRepository.save(Location.builder().name("South Korea").build());
        Genre primaryGenre = genreRepository.save(Genre.builder().name("Pop").build());
        Genre secondaryGenre = genreRepository.save(Genre.builder().name("Dance").build());
        Descriptor descriptor1 = descriptorRepository.save(Descriptor.builder().name("energetic").build());
        Descriptor descriptor2 = descriptorRepository.save(Descriptor.builder().name("electronic").build());

        Release release = Release.builder()
                .artistId(1L)
                .locationId(location.getId())
                .title("Test Album")
                .releaseType(ReleaseType.ALBUM)
                .releaseDate(LocalDate.now())
                .build();

        // ReleaseGenre 연관관계 추가
        release.getReleaseGenres().add(ReleaseGenre.builder()
                .release(release).genre(primaryGenre).isPrimary(true).order(1).build());
        release.getReleaseGenres().add(ReleaseGenre.builder()
                .release(release).genre(secondaryGenre).isPrimary(false).order(2).build());

        // ReleaseDescriptor 연관관계 추가
        release.getReleaseDescriptors().add(ReleaseDescriptor.builder()
                .release(release).descriptor(descriptor1).order(1).build());
        release.getReleaseDescriptors().add(ReleaseDescriptor.builder()
                .release(release).descriptor(descriptor2).order(2).build());

        // ReleaseLanguage 연관관계 추가
        release.getReleaseLanguages().add(ReleaseLanguage.builder()
                .release(release).language(Language.KO).build());

        // when
        releaseRepository.saveAndFlush(release);
        em.clear();

        // then
        Release foundRelease = releaseRepository.findById(release.getId()).orElseThrow();
        
        // Location 확인
        assertThat(foundRelease.getLocationId()).isEqualTo(location.getId());

        // Genre 확인 (isPrimary=true, false 모두 포함)
        List<ReleaseGenre> genres = foundRelease.getReleaseGenres();
        assertThat(genres).hasSize(2);
        assertThat(genres).extracting("genre.name").containsExactlyInAnyOrder("Pop", "Dance");
        assertThat(genres.stream().filter(ReleaseGenre::getIsPrimary).count()).isEqualTo(1);

        // Descriptor 확인
        List<ReleaseDescriptor> descriptors = foundRelease.getReleaseDescriptors();
        assertThat(descriptors).hasSize(2);
        assertThat(descriptors).extracting("descriptor.name").containsExactlyInAnyOrder("energetic", "electronic");

        // Language 확인
        List<ReleaseLanguage> languages = foundRelease.getReleaseLanguages();
        assertThat(languages).hasSize(1);
        assertThat(languages.get(0).getLanguage()).isEqualTo(Language.KO);
        assertThat(languages.get(0).getLanguage().getDisplayName()).isEqualTo("Korean");
    }
}
