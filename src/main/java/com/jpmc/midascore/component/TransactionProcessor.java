package com.jpmc.midascore.component;

import com.jpmc.midascore.entity.TransactionRecord;
import com.jpmc.midascore.entity.UserRecord;
import com.jpmc.midascore.foundation.Incentive;
import com.jpmc.midascore.foundation.Transaction;
import com.jpmc.midascore.repository.TransactionRepository;
import com.jpmc.midascore.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

@Component
public class TransactionProcessor {

    private static final Logger logger = LoggerFactory.getLogger(TransactionProcessor.class);

    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final RestTemplate restTemplate;

    public TransactionProcessor(UserRepository userRepository,
                                TransactionRepository transactionRepository,
                                RestTemplateBuilder restTemplateBuilder) {
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.restTemplate = restTemplateBuilder.build();
    }

    @Transactional
    public void process(Transaction transaction) {
        if (transaction == null) {
            return;
        }

        long senderId = transaction.getSenderId();
        long recipientId = transaction.getRecipientId();
        float amount = transaction.getAmount();

        UserRecord sender = userRepository.findById(senderId);
        UserRecord recipient = userRepository.findById(recipientId);

        // Validate sender and recipient exist
        if (sender == null || recipient == null) {
            logger.debug("Discarding transaction {} -> {} for {}: invalid user id(s)", senderId, recipientId, amount);
            return;
        }

        // Validate sender has sufficient balance
        if (sender.getBalance() < amount) {
            logger.debug("Discarding transaction {} -> {} for {}: insufficient funds", senderId, recipientId, amount);
            return;
        }

        // Fetch incentive from external Incentive API
        float incentiveAmount = 0.0f;
        try {
            Incentive incentive = restTemplate.postForObject(
                    "http://localhost:8080/incentive",
                    transaction,
                    Incentive.class
            );
            if (incentive != null) {
                incentiveAmount = incentive.getAmount();
            }
        } catch (Exception e) {
            logger.warn("Failed to fetch incentive for transaction {}: {}", transaction, e.getMessage());
        }

        // Apply balance changes (sender loses amount, recipient gains amount + incentive)
        sender.setBalance(sender.getBalance() - amount);
        recipient.setBalance(recipient.getBalance() + amount + incentiveAmount);

        // Persist updated users and transaction record
        userRepository.save(sender);
        userRepository.save(recipient);

        TransactionRecord record = new TransactionRecord(sender, recipient, amount, incentiveAmount);
        transactionRepository.save(record);

        logger.info("Processed transaction {} -> {} for {} with incentive {}", senderId, recipientId, amount, incentiveAmount);
    }
}

