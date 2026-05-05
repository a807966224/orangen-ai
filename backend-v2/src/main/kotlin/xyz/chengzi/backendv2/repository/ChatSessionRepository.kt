package xyz.chengzi.backendv2.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import xyz.chengzi.backendv2.entity.ChatSession

@Repository
interface ChatSessionRepository : JpaRepository<ChatSession, String>