package nnz.nanumservice.service.impl;

import com.google.firebase.messaging.FirebaseMessagingException;
import io.github.eello.nnz.common.exception.CustomException;
import io.github.eello.nnz.common.kafka.KafkaMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nnz.nanumservice.dto.CertificationDTO;
import nnz.nanumservice.dto.FcmNotificationDTO;
import nnz.nanumservice.dto.NanumDTO;
import nnz.nanumservice.dto.UserNanumDTO;
import nnz.nanumservice.exception.ErrorCode;
import nnz.nanumservice.entity.Nanum;
import nnz.nanumservice.entity.NanumStock;
import nnz.nanumservice.entity.UserNanum;
import nnz.nanumservice.repository.NanumRepository;
import nnz.nanumservice.repository.NanumStockRepository;
import nnz.nanumservice.repository.UserNanumRepository;
import nnz.nanumservice.repository.UserRepository;
import nnz.nanumservice.service.CertificationService;
import nnz.nanumservice.service.KafkaProducer;
import nnz.nanumservice.vo.NanumCertificationVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class CertificationServiceImpl implements CertificationService {

    private final NanumRepository nanumRepository;
    private final UserRepository userRepository;
    private final UserNanumRepository userNanumRepository;
    private final NanumStockRepository nanumStockRepository;
    private final FCMService fcmService;
    private final KafkaProducer producer;

    @Override
    public void handleNanumCertification(Long nanumId, NanumCertificationVO nanumCertificationVO) throws FirebaseMessagingException {
        Nanum nanum = nanumRepository.findById(nanumId)
                .orElseThrow(() -> new CustomException(ErrorCode.NANUM_NOT_FOUND));

        UserNanum userNanum = userNanumRepository.findById(nanumCertificationVO.getId())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NANUM_NOT_FOUND));

        // 이미 처리한 인증인지 확인
        if(userNanum.getIsCertificated() != null) throw new CustomException(ErrorCode.ALREADY_USER_NANUM);

        // true : 인증 성공, false : 인증 실패
        userNanum.updateIsCertificated(nanumCertificationVO.getCertification());

        UserNanumDTO userNanumDTO = UserNanumDTO.of(userNanum);
        KafkaMessage<UserNanumDTO> userNanumDTOKafkaMessage = KafkaMessage.update().body(userNanumDTO);
        producer.sendMessage(userNanumDTOKafkaMessage, "usernanum");

        int countByIsCertificated = userNanumRepository.countByNanumAndIsCertificated(nanum, true);
        if(countByIsCertificated == nanum.getQuantity()){
            nanum.updateStatus(1);
            // 마감되었을 때 PUSH 알림
            fcmService.sendMessage(FcmNotificationDTO.builder()
                            .title("내가 만든 나눔이 마감되었어요.")
                            .body(nanum.getIsCertification() ? "인증을 검사하러 가볼까요?" : "")
                            .userToken(nanum.getProvider().getDeviceToken())
                            .build());

            KafkaMessage<NanumDTO> kafkaMessage = KafkaMessage.update().body(NanumDTO.of(nanum));
            producer.sendMessage(kafkaMessage, "nanum");
        }
    }

    @Override
    public NanumStock certifyQRCode(Long nanumId, Long receiveId) {

        Nanum nanum = nanumRepository.findById(nanumId)
                .orElseThrow(() -> new CustomException(ErrorCode.NANUM_NOT_FOUND));

        UserNanum userNanum = userNanumRepository.findById(receiveId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NANUM_NOT_FOUND));

        // 인증 거부된 나눔 신청일 경우
        if(!userNanum.getIsCertificated()) throw new CustomException(ErrorCode.USER_NANUM_NOT_CERTIFIED);

        // 이미 수령한 나눔일 경우
        if(userNanum.getIsReceived()) throw new CustomException(ErrorCode.ALREADY_RECEIVED_USER_NANUM);

        Optional<NanumStock> optionalNanumStock = nanumStockRepository.findById(nanumId);

        // 맨처음 들어온 QR인증이라면 재고 정보 저장
        NanumStock ns = optionalNanumStock.orElseGet(() -> nanumStockRepository.save(NanumStock.builder()
                .id(nanumId)
                .quantity(nanum.getQuantity())
                .stock(nanum.getQuantity())
                .build()));

        // 수량과 재고가 같다면 이제 나눔이 시작됐다는 뜻이므로 상태를 진행중으로 바꾼다.
        if(ns.getStock() == nanum.getQuantity()) {
            nanum.updateStatus(2);
            KafkaMessage<NanumDTO> kafkaMessage = KafkaMessage.update().body(NanumDTO.of(nanum));
            producer.sendMessage(kafkaMessage, "nanum");
        }

        // 현재 진행중인 나눔이 아닐 경우
        if(nanum.getStatus() != 2) throw new CustomException(ErrorCode.NANUM_NOT_ACTIVE);

        // 재고 감소
        ns.minusStock();

        // UserNanum isReceived 상태 변경
        userNanum.updateIsReceived();

        UserNanumDTO userNanumDTO = UserNanumDTO.of(userNanum);
        KafkaMessage<UserNanumDTO> userNanumDTOKafkaMessage = KafkaMessage.update().body(userNanumDTO);
        producer.sendMessage(userNanumDTOKafkaMessage, "usernanum");

        NanumStock save = nanumStockRepository.save(ns);

        // 재고가 0이면 나눔 종료 and 재고 0
        if(save.getStock() == 0){
            nanum.updateStatus(2);
            nanum.updateStock(0);

            KafkaMessage<NanumDTO> kafkaMessage = KafkaMessage.update().body(NanumDTO.of(nanum));
            producer.sendMessage(kafkaMessage, "nanum");
        }

        // 남은 수량 return
        return save;
    }

    @Override
    public List<CertificationDTO> findCertificationList(Long nanumId) {

        Nanum nanum = nanumRepository.findById(nanumId)
                .orElseThrow(() -> new CustomException(ErrorCode.NANUM_NOT_FOUND));

        List<UserNanum> allByNanum = userNanumRepository.findAllByNanum(nanum);

        return allByNanum.stream().map(userNanum -> CertificationDTO.builder()
                .id(userNanum.getId())
                .email(userNanum.getReceiver().getEmail())
                .image(userNanum.getCertificationImage())
                .build()).collect(Collectors.toList());
    }
}
