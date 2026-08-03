package com.recall.capture;

import org.springframework.data.jpa.repository.JpaRepository;

/** capture 저장/조회 창구. JpaRepository 상속만으로 save/findById/findAll 등이 자동 제공된다. */
public interface CaptureRepository extends JpaRepository<Capture, Long> {}
