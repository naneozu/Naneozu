package nnz.userservice.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.eello.nnz.common.dto.PageDTO;
import io.github.eello.nnz.common.exception.CustomException;
import io.github.eello.nnz.common.jwt.DecodedToken;
import io.github.eello.nnz.common.kafka.KafkaMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nnz.userservice.dto.*;
import nnz.userservice.dto.sync.UserSyncDTO;
import nnz.userservice.entity.*;
import nnz.userservice.exception.ErrorCode;
import nnz.userservice.repository.*;
import nnz.userservice.service.JwtProvider;
import nnz.userservice.kafka.KafkaProducer;
import nnz.userservice.service.S3Service;
import nnz.userservice.service.UserService;
import nnz.userservice.util.ValidationUtils;
import nnz.userservice.vo.FindPwdVO;
import nnz.userservice.vo.LoginVO;
import nnz.userservice.vo.UserJoinVO;
import nnz.userservice.vo.UserUpdateProfileVO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {

    private static final String TOPIC = "user";

    private final UserRepository userRepository;
    private final VerifyNumberRepository verifyNumberRepository;
    private final PasswordEncoder passwordEncoder;
    private final KafkaProducer kafkaProducer;
    private final JwtProvider jwtProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AccessTokenBlacklistRepository accessTokenBlacklistRepository;
    private final BookmarkRepository bookmarkRepository;
    private final NanumRepository nanumRepository;
    private final ReceiveNanumRepository receiveNanumRepository;
    private final FollowRepository followRepository;
    private final S3Service s3Service;

    @Override
    @Transactional
    public UserDTO join(UserJoinVO vo) throws UnsupportedEncodingException, JsonProcessingException {
        // 본인인증이 확인되었는지 체크
        VerifyNumber vn = verifyNumberRepository.findById(vo.getPhone())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND_VERIFY));

        if (!vn.isVerify()) {
            throw new CustomException(ErrorCode.NOT_VERIFIED);
        }

        // 이메일 형식 검사
        if (!ValidationUtils.isValidEmail(vo.getEmail())) {
            throw new CustomException(ErrorCode.INVALID_EMAIL_PATTERN);
        }

        // 이메일 중복 검사
        if (isExistByEmail(vo.getEmail())) {
            throw new CustomException(ErrorCode.DUPLICATE_EMAIL);
        }

        // 폰 번호 중복 검사
        if (isExistsByPhoneNumber(vo.getPhone())) {
            throw new CustomException(ErrorCode.DUPLICATE_PHONE_NUMBER);
        }

        // 닉네임 형식 검사
        if (!ValidationUtils.isValidNickname(vo.getNickname())) {
            throw new CustomException(ErrorCode.INVALID_NICKNAME_PATTERN);
        }

        // 닉네임 중복 검사
        if (isExistByNickname(vo.getNickname())) {
            throw new CustomException(ErrorCode.DUPLICATE_NICKNAME);
        }

        // 비밀번호 형식 검사
        if (!ValidationUtils.isValidPwd(vo.getPwd())) {
            throw new CustomException(ErrorCode.INVALID_PWD_PATTERN);
        }

        // 비밀번호와 비밀번호 확인이 일치하는지 확인
        if (!vo.getPwd().equals(vo.getConfirmPwd())) {
            throw new CustomException(ErrorCode.PWD_NOT_MATCH_CONFIRM_PWD);
        }

        User newUser = User.builder()
                .email(vo.getEmail())
                .nickname(vo.getNickname())
                .phoneNumber(vo.getPhone())
                .password(passwordEncoder.encode(vo.getPwd()))
                .authProvider(User.AuthProvider.NNZ)
                .role(User.Role.USER)
                .build();

        newUser = userRepository.save(newUser);
        log.info("새로운 유저 가입: {}", newUser);

        KafkaMessage<UserDTO> kafkaMessage =
                KafkaMessage.create().body(UserSyncDTO.of(newUser));

        // kafka에 가입한 사용자에 대한 메시지 발생
        kafkaProducer.sendMessage(TOPIC, kafkaMessage);

        return UserDTO.of(newUser);
    }

    @Override
    public boolean isExistByEmail(String email) {
        return userRepository.existsByEmail(email);
    }

    @Override
    public boolean isExistByNickname(String nickname) {
        return userRepository.existsByNickname(nickname);
    }

    @Override
    public boolean isExistsByPhoneNumber(String phoneNumber) {
        return userRepository.existsByPhoneNumber(phoneNumber);
    }

    @Override
    @Transactional
    public TokenDTO login(LoginVO vo) throws JsonProcessingException {
        User user = userRepository.findByEmail(vo.getEmail())
                .orElseThrow(() -> new CustomException(ErrorCode.LOGIN_FAILURE));

        // 로그인 실패시 ErrorCode.LOGIN_FAILURE throw
        user.login(passwordEncoder, vo.getPwd());
        // 기기 정보 저장
        user.setDeviceToken(vo.getDeviceToken());

        String accessToken = jwtProvider.generateAccessToken(user);
        String refreshToken = jwtProvider.generateRefreshToken(user);

        // 레디스에 리프레시 토큰 저장 -> key: email / value: refreshToken
        RefreshToken rt = RefreshToken.builder()
                .id(user.getEmail())
                .refreshToken(refreshToken)
                .build();
        refreshTokenRepository.save(rt);

        // 변경된 유저 데이터 동기화 미시지 전송
        KafkaMessage kafkaMessage = KafkaMessage.update().body(UserSyncDTO.of(user));
        kafkaProducer.sendMessage(TOPIC, kafkaMessage);

        return TokenDTO.builder()
                .userId(user.getId())
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    @Override
    @Transactional
    public void logout(String accessToken, DecodedToken token) {
        // 로그인되어있는 유저 조회
        User user = userRepository.findById(token.getId())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 레디스 블랙리스트에 액세스 토큰 저장 ttl = 현재시간 - 만료시간
        long ttl = (token.getExpDate().getTime() - System.currentTimeMillis()) / 1000;
        log.info("logout: ttl -> {}", ttl);
        AccessTokenBlacklist blacklist = AccessTokenBlacklist.builder()
                .id(accessToken)
                .ttl(ttl)
                .build();

        accessTokenBlacklistRepository.save(blacklist);
        log.info("logout: 블랙리스트에 엑세스 토큰 추가");

        // 리프레시 토큰 삭제
        RefreshToken refreshToken = refreshTokenRepository.findById(user.getEmail())
                .orElseThrow(() -> new CustomException(ErrorCode.REFRESH_TOKEN_NOT_FOUND));
        refreshTokenRepository.delete(refreshToken);
        log.info("logout: 리프레시 토큰 삭제");

        log.info("logout: {}님 로그아웃", user.getEmail());
    }

    @Override
    @Transactional
    public void findPwd(FindPwdVO vo) {
        VerifyNumber vn = verifyNumberRepository.findById(vo.getPhone())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND_VERIFY));

        // 본인확인 검증
        if (!vn.isVerify()) {
            throw new CustomException(ErrorCode.NOT_VERIFIED);
        }

        // 비밀번호와 비밀번호 확인이 일치하지 않음
        if (!vo.getPwd().equals(vo.getConfirmPwd())) {
            throw new CustomException(ErrorCode.PWD_NOT_MATCH_CONFIRM_PWD);
        }

        // 비밃번호 형식이 유효하지 않음
        if (!ValidationUtils.isValidPwd(vo.getPwd())) {
            throw new CustomException(ErrorCode.INVALID_PWD_PATTERN);
        }

        User user = userRepository.findByPhoneNumber(vo.getPhone())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 패스워드 업데이트
        user.changePwd(passwordEncoder.encode(vo.getPwd()));

        log.info("{}님의 비밀번호가 변경되었습니다.", user.getEmail());
    }

    @Override
    public List<BookmarkedNanumDTO> findBookmarkedNanum(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        List<Nanum> bookmarkedNanum = bookmarkRepository.findNanumByUser(user);
        // TODO: 나눔마다 태그 조회 쿼리 실행되는 점 개선
//        nanumTagRepository.findByNanumIn(bookmarkedNanum);
        return bookmarkedNanum.stream().map(BookmarkedNanumDTO::of).collect(Collectors.toList());
    }

    @Override
    public UserDTO info(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        Integer followingCount = followRepository.countByFollowerAndIsDeleteFalse(user);
        Integer followerCount = followRepository.countByFollowingAndIsDeleteFalse(user);

        List<Nanum> provideNanum = nanumRepository.findByProvider(user);
        StatisticsDTO provideStatistics = StatisticsDTO.of(provideNanum);

        List<Nanum> receiveNanum = receiveNanumRepository.findNanumByReceiver(user);
        StatisticsDTO receiveStatistics = StatisticsDTO.of(receiveNanum);

        UserDTO userDTO = UserDTO.of(user);
        userDTO.setFollowingCount(followingCount);
        userDTO.setFollowerCount(followerCount);
        userDTO.setStatistics(new UserDTO.Statistics(provideStatistics, receiveStatistics));

        return userDTO;
    }

    @Override
    public PageDTO receivedNanums(Long userId, Pageable pageable) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        PageRequest pageRequest =
                PageRequest.of(
                        pageable.getPageNumber(), pageable.getPageSize(), Sort.by("updatedAt").descending()
                );

        Page<Nanum> receivedNanums = receiveNanumRepository.findNanumByReceiver(user, pageRequest);
        Page<NanumDTO> receivedNanumDTO = receivedNanums.map(NanumDTO::of);
        return PageDTO.of(receivedNanumDTO);
    }

    @Override
    public PageDTO providedNanums(Long userId, Pageable pageable) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        PageRequest pageRequest =
                PageRequest.of(
                        pageable.getPageNumber(), pageable.getPageSize(), Sort.by("updatedAt").descending()
                );

        Page<Nanum> providedNanums = nanumRepository.findByProvider(user, pageRequest);
        Page<NanumDTO> providedNanumDTO = providedNanums.map(NanumDTO::of);
        return PageDTO.of(providedNanumDTO);
    }

    @Override
    public NanumParticipantsDTO nanumParticipants(Long userId, Long nanumId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        Nanum nanum = nanumRepository.findById(nanumId)
                .orElseThrow(() -> new CustomException(ErrorCode.NANUM_NOT_FOUND));

        if (nanum.getProvider() != user) {
            throw new CustomException(ErrorCode.NOT_PROVIDER);
        }

        List<ReceiveNanum> receiveNanums = receiveNanumRepository.findByNanum(nanum); // 나눔에 참가한 유저 조회
        Set<User> follower = followRepository.findFollowerAndIsDeleteFalse(user); // 나눔자의 팔로워 조회

        List<NanumParticipantsDTO.ParticipantDTO> participants = new ArrayList<>();
        for (ReceiveNanum receiveNanum : receiveNanums) {
            User receiver = receiveNanum.getReceiver();

            boolean isFollower = follower.contains(receiver);

            NanumParticipantsDTO.ParticipantDTO participantDTO = NanumParticipantsDTO.ParticipantDTO.builder()
                    .id(receiver.getId())
                    .nickname(receiver.getNickname())
                    .profileImage(receiver.getProfileImage())
                    .isReceived(receiveNanum.isReceived())
                    .isCertificated(receiveNanum.isCertificated())
                    .isFollower(isFollower)
                    .build();

            participants.add(participantDTO);
        }

        return NanumParticipantsDTO.of(nanum, participants);
    }

    @Override
    @Transactional
    public void updateProfile(Long userId, UserUpdateProfileVO vo, MultipartFile file) throws UnsupportedEncodingException, JsonProcessingException {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 기존 비밀번호가 입력되지 않은 경우
        if (!StringUtils.hasText(vo.getOldPwd())) {
            throw new CustomException(ErrorCode.OLD_PASSWORD_IS_REQUIRED);
        }

        // 기존 비밀번호가 일치하는지 검증
        user.matchPwd(passwordEncoder, vo.getOldPwd());

        log.info("user profile update -> Before: {}", user);

        if (StringUtils.hasText(vo.getNickname())) { // 닉네임 변경 요청
            if (!ValidationUtils.isValidNickname(vo.getNickname())) { // 닉네임 형식이 맞지 않은 경우
                throw new CustomException(ErrorCode.INVALID_NICKNAME_PATTERN);
            }

            if (isExistByNickname(vo.getNickname())) { // 이미 존재하는 닉네임인 경우
                throw new CustomException(ErrorCode.DUPLICATE_NICKNAME);
            }

            user.changeNickname(vo.getNickname());
            log.info("update nickname");
        }

        if (StringUtils.hasText(vo.getNewPwd())) { // 비밀번호 변경 요청
            if (!ValidationUtils.isValidPwd(vo.getNewPwd())) { // 비밀번호 형식이 맞지 않은 경우
                throw new CustomException(ErrorCode.INVALID_PWD_PATTERN);
            }

            if (!vo.getNewPwd().equals(vo.getConfirmNewPwd())) { // 비밀번호와 비밀번호 확인이 불일치
                throw new CustomException(ErrorCode.PWD_NOT_MATCH_CONFIRM_PWD);
            }

            user.changePwd(passwordEncoder.encode(vo.getNewPwd()));
            log.info("update password");
        }

        if (!file.isEmpty()) { // 프로필 이미지 변경 요청
            try {
                String oldProfileImage = user.getProfileImage();
                String profileImagePath = s3Service.uploadFile(file);
                user.changeProfileImage(profileImagePath);
                log.info("update profile image");

                // 버켓에서 기존 프로필 이미지 파일 삭제

//                if (oldProfileImage != null) {
//                    s3Service.deleteFile(oldProfileImage);
//                    log.info("delete old profile image");
//                }
            } catch (IOException e) {
                throw new CustomException(ErrorCode.PROFILE_IMAGE_UPLOAD_FAIL);
            }
        }

        KafkaMessage kafkaMessage = KafkaMessage.update().body(UserSyncDTO.of(user));
        kafkaProducer.sendMessage(TOPIC, kafkaMessage);
        log.info("user profile update -> After: {}", user);
    }

    @Override
    public OtherUserInfoDTO otherUserInfo(Long meId, Long otherUserId) {
        // TODO: 해당 메소드를 실행하기 위해 쿼리가 6번 실행 -> 줄이는 방법을 생각

        User otherUser = userRepository.findById(otherUserId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        Integer followingCount = followRepository.countByFollowerAndIsDeleteFalse(otherUser);
        Integer followerCount = followRepository.countByFollowingAndIsDeleteFalse(otherUser);

        boolean isFollower = meId != null &&
                followRepository.existsByFollowerIdAndFollowingIdAndIsDeleteFalse(meId, otherUserId);

        Integer nanumCount = nanumRepository.countByProvider(otherUser);
        Integer receiveCount = receiveNanumRepository.countByReceiver(otherUser);
        OtherUserInfoDTO.Statistics statistics = OtherUserInfoDTO.Statistics.builder()
                .totalCount(nanumCount + receiveCount)
                .nanumCount(nanumCount)
                .receiveCount(receiveCount)
                .build();

        return OtherUserInfoDTO.builder()
                .id(otherUser.getId())
                .nickname(otherUser.getNickname())
                .profileImage(otherUser.getProfileImage())
                .isFollow(isFollower)
                .followingCount(followingCount)
                .followerCount(followerCount)
                .statistics(statistics)
                .build();
    }

    @Override
    public String reissue(TokenDTO token) {
        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        RefreshToken refreshToken = refreshTokenRepository.findById(user.getEmail())
                .orElseThrow(() -> new CustomException(ErrorCode.REFRESH_TOKEN_NOT_FOUND));

        if (!refreshToken.getRefreshToken().equals(token.getRefreshToken())) {
            throw new CustomException(ErrorCode.REFRESH_TOKEN_NOT_MATCHED);
        }

        if (!jwtProvider.validateToken(token.getRefreshToken())) {
            throw new CustomException(ErrorCode.REFRESH_TOKEN_NOT_VALID);
        }

        log.info("{}님 엑세스 토큰 재발급", user.getEmail());
        return jwtProvider.generateAccessToken(user);
    }
}
