#import <CoreGraphics/CoreGraphics.h>
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface FKZinniaResult : NSObject
@property(nonatomic, readonly, copy) NSString *text;
@property(nonatomic, readonly) float score;
- (instancetype)initWithText:(NSString *)text score:(float)score;
@end

@interface FKZinniaRecognizer : NSObject
- (nullable instancetype)initWithModelPath:(NSString *)modelPath
                                     error:(NSError * _Nullable * _Nullable)error;
- (NSArray<FKZinniaResult *> *)recognizeStrokes:(NSArray<NSArray<NSValue *> *> *)strokes
                                           width:(NSInteger)width
                                          height:(NSInteger)height
                                           limit:(NSInteger)limit;
@end

NS_ASSUME_NONNULL_END

