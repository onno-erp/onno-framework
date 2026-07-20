package com.example.repositories;

import com.example.domain.documents.ScheduleEvent;
import su.onno.repository.DocumentRepository;

/** Typed repository used by the availability decorator and demo seeder. */
public interface ScheduleEventRepository extends DocumentRepository<ScheduleEvent> {
}
