package org.jeecg.modules.device.service;

import org.jeecg.modules.device.mapper.IotLogArchiveMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IotLogArchiveServiceTest {

    private IotLogArchiveMapper mapper;
    private TransactionTemplate transactionTemplate;
    private IotLogArchiveService service;

    @BeforeEach
    void setUp() throws Exception {
        mapper = Mockito.mock(IotLogArchiveMapper.class);
        transactionTemplate = Mockito.mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        service = new IotLogArchiveService(mapper, transactionTemplate);
        Field batchSize = IotLogArchiveService.class.getDeclaredField("batchSize");
        batchSize.setAccessible(true);
        batchSize.set(service, 2);
    }

    @Test
    @DisplayName("archiveAll：主表分批归档并清理历史表")
    void archiveAllProcessesAllTables() {
        LocalDateTime mainCutoff = LocalDateTime.now().minusDays(90);
        LocalDateTime historyCutoff = LocalDateTime.now().minusDays(365);

        when(mapper.selectCommandRecordIdsBefore(eq(mainCutoff), eq(2)))
                .thenReturn(List.of("c1", "c2"))
                .thenReturn(List.of());
        when(mapper.insertCommandRecordHistory(List.of("c1", "c2"))).thenReturn(2);
        when(mapper.deleteCommandRecordsByIds(List.of("c1", "c2"))).thenReturn(2);

        when(mapper.selectOperationLogIdsBefore(eq(mainCutoff), eq(2)))
                .thenReturn(List.of("o1"))
                .thenReturn(List.of());
        when(mapper.insertOperationLogHistory(List.of("o1"))).thenReturn(1);
        when(mapper.deleteOperationLogsByIds(List.of("o1"))).thenReturn(1);

        when(mapper.selectMqMessageLogIdsBefore(eq(mainCutoff), eq(2))).thenReturn(List.of());

        when(mapper.deleteCommandRecordHistoryBefore(eq(historyCutoff), eq(2))).thenReturn(1, 0);
        when(mapper.deleteOperationLogHistoryBefore(eq(historyCutoff), eq(2))).thenReturn(0);
        when(mapper.deleteMqMessageLogHistoryBefore(eq(historyCutoff), eq(2))).thenReturn(0);

        IotLogArchiveService.ArchiveSummary summary = service.archiveAll(mainCutoff, historyCutoff);

        assertEquals(3, summary.totalArchived());
        assertEquals(1, summary.totalHistoryPurged());
        verify(mapper, times(1)).insertCommandRecordHistory(List.of("c1", "c2"));
        verify(mapper, never()).insertMqMessageLogHistory(any());
    }
}
