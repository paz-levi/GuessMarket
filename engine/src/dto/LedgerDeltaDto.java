package dto;

import java.util.List;

// The delta-polling envelope for a user's transaction ledger: entries strictly newer than the "since" cursor the
// client already holds, in ASCENDING sequence order (oldest of this batch first) so a client can simply append the
// list to what it already has and set its cursor to the batch's own version -- no client-side re-sort needed. The
// only dto type no IEngine method returns directly; the servlet layer builds it from UserDetailDto.transactions()
// (which is newest-first) by filtering to sequence > since and reversing.
public record LedgerDeltaDto(
        int version,
        List<TransactionRecordDto> entries
) {
}
